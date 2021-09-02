package dpf.sp.gpinf.indexer.process.task;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.tika.Tika;
import org.apache.tika.io.TemporaryResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dpf.sp.gpinf.indexer.CmdLineArgs;
import dpf.sp.gpinf.indexer.config.ConfigurationManager;
import dpf.sp.gpinf.indexer.config.MinIOConfig;
import dpf.sp.gpinf.indexer.util.SeekableInputStreamFactory;
import dpf.sp.gpinf.network.util.ProxySever;
import gpinf.dev.data.Item;
import io.minio.BucketExistsArgs;
import io.minio.ErrorCode;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.ObjectStat;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidBucketNameException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import iped3.ICaseData;
import iped3.IItem;
import iped3.io.SeekableInputStream;
import macee.core.Configurable;

/**
 * Task to export files to MinIO object storage service.
 * 
 * TODO: This and @ExportFileTask should extend a common abstract class, and the
 * implementation should be chosen depending on configuration.
 * 
 * @author Nassif
 *
 */
public class MinIOTask extends AbstractTask {

    private static Logger logger = LoggerFactory.getLogger(MinIOTask.class);

    private static final int FOLDER_LEVELS = 2;
    private static final String CMD_LINE_KEY = "MinioCredentials";
    private static final String ACCESS_KEY = "accesskey";
    private static final String SECRET_KEY = "secretkey";
    private static final String BUCKET_KEY = "bucket";

    private static String accessKey;
    private static String secretKey;
    private static String bucket = null;

    private static Tika tika;

    private MinIOConfig minIOConfig;
    private MinioClient minioClient;
    private MinIOInputInputStreamFactory inputStreamFactory;

    private static long tarMaxLength = 0;
    private static long tarMaxFiles = 0;
    private TemporaryResources tmp = null;
    private TarArchiveOutputStream out = null;
    private File tarfile = null;
    private long tarLength = 0;
    private long tarFiles = 0;
    private HashSet<IItem> queue;
    private boolean sendQueue = false;

    @Override
    public void init(ConfigurationManager configurationManager) throws Exception {

        minIOConfig = configurationManager.findObject(MinIOConfig.class);

        if (!minIOConfig.isEnabled()) {
            return;
        }
        queue = new HashSet<>();
        String server = minIOConfig.getHost() + ":" + minIOConfig.getPort();

        tarMaxLength = minIOConfig.getTarMaxLength();
        tarMaxFiles = minIOConfig.getTarMaxFiles();

        // case name is default bucket name
        if (bucket == null) {
            bucket = output.getParentFile().getName().toLowerCase();
        }
        loadCredentials(caseData);

        minioClient = MinioClient.builder().endpoint(server).credentials(accessKey, secretKey).build();
        inputStreamFactory = new MinIOInputInputStreamFactory(URI.create(server));

        // Check if the bucket already exists.
        boolean isExist = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!isExist) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }

    }

    private static void loadCredentials(ICaseData caseData) {
        if (accessKey != null && secretKey != null) {
            return;
        }
        String cmdFields = null;
        if (caseData != null) {
            CmdLineArgs args = (CmdLineArgs) caseData.getCaseObject(CmdLineArgs.class.getName());
            cmdFields = args.getExtraParams().get(CMD_LINE_KEY);
        }
        if (cmdFields == null) {
            cmdFields = System.getProperty(CMD_LINE_KEY);
        }
        if (cmdFields == null) {
            cmdFields = System.getenv(CMD_LINE_KEY);
        }
        if (cmdFields == null) {
            throw new RuntimeException("'MinioCredentials' not set by ENV var, sys prop or cmd line param.");
        }
        String[] entries = cmdFields.split(";");
        for (String entry : entries) {
            String[] pair = entry.split(":", 2);
            if (ACCESS_KEY.equals(pair[0]))
                accessKey = pair[1];
            else if (SECRET_KEY.equals(pair[0]))
                secretKey = pair[1];
            else if (BUCKET_KEY.equals(pair[0]))
                bucket = pair[1];
        }
    }

    @Override
    public boolean isEnabled() {
        return minIOConfig.isEnabled();
    }

    public List<Configurable<?>> getConfigurables() {
        return Arrays.asList(new MinIOConfig());
    }

    public static boolean isTaskEnabled() {
        MinIOConfig minIOConfig = ConfigurationManager.get().findObject(MinIOConfig.class);
        return minIOConfig.isEnabled();
    }

    @Override
    public void finish() throws Exception {
        flushTarFile();
    }

    private void flushTarFile() throws Exception {
        if (tarfile != null) {
            logger.info("Flushing MinIOTask " + worker.id + " Sending tar containing " + tarFiles + " files");
            sendTarFile();
        }
    }



    private void insertInTarFile(String bucketPath, long length, InputStream is, IItem i, boolean preview)
            throws Exception {
        if (out == null) {
            tarLength = 0;
            tmp = new TemporaryResources();
            tarfile = tmp.createTemporaryFile();
            out = new TarArchiveOutputStream(new FileOutputStream(tarfile));
        }

        // insert into the queue of files waiting to be sent
        if (!preview) {
            queue.add(i);
        }

        tarFiles++;
        tarLength += length;
        TarArchiveEntry entry = new TarArchiveEntry(bucketPath);
        entry.setSize(length);
        out.putArchiveEntry(entry);

        IOUtils.copy(is, out);
        out.closeArchiveEntry();

    }



    private String insertWithTar(IItem i, String hash, InputStream is, long length, String mediatype, boolean preview)
            throws Exception {

        String bucketPath = buildPath(hash);
        // if preview saves in a preview folder
        if (preview) {
            bucketPath = "preview/" + hash;
        }
        String fullPath = bucket + "/" + bucketPath;
        // if empty or already exists do not continue
        if (length <= 0) {

            return fullPath;
        }
       
        if (checkIfExists(bucketPath)) {
            return fullPath;
        }

        // if files are greater than 20% of tarMaxLength send directly
        if (length > tarMaxLength * 0.2) {
            insertItem(hash, is, length, mediatype, bucketPath);
        } else {

            insertInTarFile(bucketPath, length, is, i, preview);

        }

        return fullPath;

    }



    private void sendTarFile() throws Exception {
        out.close();

        if (tarFiles > 0) {
            try (InputStream fi = new BufferedInputStream(new FileInputStream(tarfile))) {

                ObjectWriteResponse aux = minioClient.putObject(
                        PutObjectArgs.builder().bucket(bucket).object(tarfile.getName().replace(".tmp", ".tar"))
                                .stream(fi, tarfile.length(), Math.max(tarfile.length(), 1024 * 1024 * 5))
                                .userMetadata(Collections.singletonMap("snowball-auto-extract", "true")).build());

            }
        }
        // mark to send items to next tasks
        sendQueue = true;

        tmp.close();
        tarFiles = 0;
        tarLength = 0;
        out = null;
        tmp = null;
        tarfile = null;
    }

    private void sendTarItemsToNextTask() throws Exception {
        for (IItem i : queue) {
            super.sendToNextTask(i);
        }
        queue.clear();
        sendQueue=false;
    }

    private boolean checkIfExists(String hash) throws Exception {
        boolean exists = false;
        try {
            ObjectStat stat = minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(hash).build());
            exists = true;

        } catch (ErrorResponseException e) {
            ErrorCode code = e.errorResponse().errorCode();
            if (code != ErrorCode.NO_SUCH_OBJECT && code != ErrorCode.NO_SUCH_KEY) {
                throw e;
            }
        }


        return exists;
    }


    private void insertItem(String hash, InputStream is, long length, String mediatype, String bucketPath)
            throws Exception {
       
        // create directory structure
        if (FOLDER_LEVELS > 0) {
            String folder = bucketPath.substring(0, FOLDER_LEVELS * 2);
            minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(folder)
                    .stream(new ByteArrayInputStream(new byte[0]), 0, -1).build());
        }

        try {
            minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(bucketPath).stream(is, length, -1)
                    .contentType(mediatype).build());



        } catch (Exception e) {
            throw new Exception("Error when uploading object ", e);
        }

    }

    private static String getMimeType(String name) {
        if (tika == null) {
            synchronized (MinIOTask.class) {
                if (tika == null) {
                    tika = new Tika();
                }
            }
        }
        return tika.detect(name);
    }

    @Override
    protected boolean processQueueEnd() {
        // TODO Auto-generated method stub
        return true;
    }

    @Override
    protected void sendToNextTask(IItem item) throws Exception {
        // if queue contains the item it will be sent when the tarfile is sent;
        if (!queue.contains(item)) {
            if (item.isQueueEnd() && !queue.isEmpty()) {
                flushTarFile();
            }

            super.sendToNextTask(item);

        }

        if (sendQueue) {
            sendTarItemsToNextTask();
        }

    }

    @Override
    protected void process(IItem item) throws Exception {

        if (item.isQueueEnd()) {
            flushTarFile();
            return;
        }

        if (caseData.isIpedReport() || !item.isToAddToCase())
            return;

        String hash = item.getHash();
        if (hash == null || hash.isEmpty() || item.getLength() == null)
            return;

        // disable blocking proxy possibly enabled by HtmlViewer
        ProxySever.get().disable();

        try (SeekableInputStream is = item.getStream()) {
            String fullPath = insertWithTar(item, hash, new BufferedInputStream(item.getStream()), is.size(),
                    item.getMediaType().toString(), false);
            if (fullPath != null) {
                updateDataSource(item, fullPath);
            }
        } catch (Exception e) {
            // TODO: handle exception
            logger.error(e.getMessage() + "File " + item.getPath() + " (" + item.getLength() + " bytes)", e);
        }
        if (item.getViewFile() != null) {
            try (InputStream is = new FileInputStream(item.getViewFile())) {
                String fullPath = insertWithTar(item, hash, new FileInputStream(item.getViewFile()),
                        item.getViewFile().length(), getMimeType(item.getViewFile().getName()), true);
                if (fullPath != null) {
                    item.getMetadata().add(ElasticSearchIndexTask.PREVIEW_IN_DATASOURCE,
                            "idInDataSource" + ElasticSearchIndexTask.KEY_VAL_SEPARATOR + fullPath);
                    item.getMetadata().add(ElasticSearchIndexTask.PREVIEW_IN_DATASOURCE, "type"
                            + ElasticSearchIndexTask.KEY_VAL_SEPARATOR + getMimeType(item.getViewFile().getName()));
                }
            } catch (Exception e) {
                // TODO: handle exception
                logger.error(e.getMessage() + "Preview " + item.getViewFile().getPath() + " ("
                        + item.getViewFile().length() + " bytes)", e);
            }
        }

        if (tarLength > tarMaxLength || tarFiles >= tarMaxFiles) {

            sendTarFile();
        }

    }

    private static String buildPath(String hash) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FOLDER_LEVELS; i++) {
            sb.append(hash.charAt(i)).append("/");
        }
        sb.append(hash);
        return sb.toString();
    }

    private static String[] parseBucketAndPath(String path) {
        return path.split("/", 2);
    }

    private void updateDataSource(IItem item, String id) {
        if (item.isSubItem()) {
            // deletes local sqlite content after sent to minio
            item.setDeleteFile(true);
            ((Item) item).dispose(false);
        }

        item.setInputStreamFactory(inputStreamFactory);
        item.setIdInDataSource(id);
        item.setFile(null);
        item.setExportedFile(null);
        item.setFileOffset(-1);
    }

    public static class MinIOInputInputStreamFactory extends SeekableInputStreamFactory {

        private static Map<String, MinioClient> map = new ConcurrentHashMap<>();

        public MinIOInputInputStreamFactory(URI dataSource) {
            super(dataSource);
        }

        public boolean checkIfDataSourceExists() {
            return false;
        }

        @Override
        public SeekableInputStream getSeekableInputStream(String identifier) throws IOException {
            String server = dataSource.toString();
            String[] parts = parseBucketAndPath(identifier);
            String bucket = parts[0];
            String path = parts[1];

            MinioClient minioClient = map.get(server);
            if (minioClient == null) {
                loadCredentials(null);
                minioClient = MinioClient.builder().endpoint(server).credentials(accessKey, secretKey).build();
                map.put(server, minioClient);
            }
            return new MinIOSeekableInputStream(minioClient, bucket, path);
        }

    }

    public static class MinIOSeekableInputStream extends SeekableInputStream {

        private MinioClient minioClient;
        private String bucket, id;
        private Long size;
        private long pos = 0;
        private InputStream is;

        public MinIOSeekableInputStream(MinioClient minioClient, String bucket, String id) {
            this.minioClient = minioClient;
            this.bucket = bucket;
            this.id = id;
            // disable blocking proxy possibly enabled by HtmlViewer
            ProxySever.get().disable();
        }

        @Override
        public void seek(long pos) throws IOException {
            this.pos = pos;
            if (is != null) {
                is.close();
                is = getInputStream(pos);
            }
        }

        @Override
        public long position() throws IOException {
            return pos;
        }

        @Override
        public long size() throws IOException {
            if (size == null) {
                try {
                    size = minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(id).build()).length();
                } catch (InvalidKeyException | ErrorResponseException | InsufficientDataException | InternalException
                        | InvalidBucketNameException | InvalidResponseException | NoSuchAlgorithmException
                        | ServerException | XmlParserException e) {
                    throw new IOException(e);
                }
            }
            return size;
        }

        @Override
        public int available() throws IOException {
            long avail = size() - pos;
            return (int) Math.min(avail, Integer.MAX_VALUE);
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int i;
            do {
                i = read(b, 0, 1);
            } while (i == 0);

            if (i == -1)
                return -1;
            else
                return b[0];
        }

        @Override
        public int read(byte b[], int off, int len) throws IOException {

            if (is == null) {
                is = getInputStream(pos);
            }

            int read = is.read(b, off, len);
            if (read != -1) {
                pos += read;
            }
            return read;
        }

        private InputStream getInputStream(long pos) throws IOException {
            try {
                return minioClient.getObject(bucket, id, pos);

            } catch (InvalidKeyException | ErrorResponseException | InsufficientDataException | InternalException
                    | InvalidBucketNameException | InvalidResponseException | NoSuchAlgorithmException | ServerException
                    | XmlParserException e) {
                throw new IOException(e);
            }
        }

        @Override
        public long skip(long n) throws IOException {

            long oldPos = pos;
            pos += n;

            if (pos > size())
                pos = size();
            else if (pos < 0)
                pos = 0;

            if (is != null) {
                is.close();
                is = getInputStream(pos);
            }

            return pos - oldPos;

        }

        @Override
        public void close() throws IOException {
            if (is != null) {
                is.close();
                is = null;
            }
        }

    }

}
