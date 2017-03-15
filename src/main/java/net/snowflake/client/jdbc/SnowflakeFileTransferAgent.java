/*
 * Copyright (c) 2012-2017 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.client.jdbc;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.SDKGlobalConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.util.Base64;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.ByteStreams;
import com.google.common.io.CountingOutputStream;
import net.snowflake.client.core.SFException;
import net.snowflake.client.core.SFFixedViewResultSet;
import net.snowflake.client.core.SFSession;
import net.snowflake.client.core.SFStatement;
import net.snowflake.common.core.SqlState;
import net.snowflake.common.util.ClassUtil;
import net.snowflake.common.core.S3FileEncryptionMaterial;
import net.snowflake.common.util.FixedViewColumn;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.DigestOutputStream;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import net.snowflake.client.log.SFLogger;
import net.snowflake.client.log.SFLoggerFactory;

/**
 * Class for uploading/downloading files
 *
 * @author jhuang
 */
public class SnowflakeFileTransferAgent implements SnowflakeFixedView
{
  final static SFLogger logger =
  SFLoggerFactory.getLogger(SnowflakeFileTransferAgent.class);

  private static final ObjectMapper mapper = new ObjectMapper();

  final static int S3_TRANSFER_MAX_RETRIES = 3;

  final static int CLIENT_SIDE_MAX_RETRIES = 25;

  // min number of milliseconds to sleep before retry
  final static int CLIENT_SIDE_RETRY_BACKOFF_MIN = 1000;

  // max exponent for multiplying backoff with the power of 2, the value
  // of 4 will give us 16secs as the max number of time to sleep before retry
  final static int CLIENT_SIDE_RETRY_BACKOFF_MAX_EXPONENT = 4;

  // We will allow buffering of upto 128M data before spilling to disk during
  // compression and digest computation
  final static int MAX_BUFFER_SIZE = 1 << 27;
  public static final String SRC_FILE_NAME_FOR_STREAM = "stream";

  // expired AWS token error code
  private static final String EXPIRED_AWS_TOKEN_ERROR_CODE = "ExpiredToken";

  private static final String FILE_PROTOCOL = "file://";

  static private String localFSFileSep = System.getProperty("file.separator");
  static private int DEFAULT_PARALLEL = 10;

  private String command;

  // list of files specified. Wildcard should be expanded already for uploading
  // For downloading, it the list of stage file names
  private Set<String> sourceFiles;

  // big source files >=16MB, for which we will not upload them in serial mode
  // since TransferManager will parallelize upload
  private Set<String> bigSourceFiles;

  // big source files < 16MB, for which we will upload them in paralle mode
  // with 4 threads by default
  private Set<String> smallSourceFiles;

  static final private int BIG_FILE_THRESHOLD = 16 * 1024 * 1024;

  private Map<String, FileMetadata> fileMetadataMap;

  // stage related info
  private String stageLocationType;
  private String stageLocation;
  private Map stageCredentials;
  private String stageRegion;

  // local location for where to download files to
  private String localLocation;

  private boolean showEncryptionParameter;

  // default parallelism
  private int parallel = DEFAULT_PARALLEL;

  private SFSession connection;
  private SFStatement statement;

  private InputStream sourceStream;
  private boolean sourceFromStream;
  private long sourceStreamSize;
  private boolean compressSourceFromStream;

  private String destFileNameForStreamSource;


  // Encryption material
  private List<S3FileEncryptionMaterial> encryptionMaterial;

  // Index: Source file to encryption material
  HashMap<String, S3FileEncryptionMaterial> srcFileToEncMat;

  private void initEncryptionMaterial(CommandType commandType, JsonNode jsonNode)
      throws SnowflakeSQLException, JsonProcessingException
  {
    encryptionMaterial = new ArrayList<>();
    JsonNode rootNode = jsonNode.path("data").path("encryptionMaterial");
    if (commandType == CommandType.UPLOAD)
    {
      logger.debug("initEncryptionMaterial: UPLOAD");

      S3FileEncryptionMaterial encMat = null;
      if (!rootNode.isMissingNode() && !rootNode.isNull())
      {
        encMat = mapper.treeToValue(rootNode, S3FileEncryptionMaterial.class);
      }
      encryptionMaterial.add(encMat);

    }
    else
    {
      logger.debug("initEncryptionMaterial: DOWNLOAD");

      if (!rootNode.isMissingNode() && !rootNode.isNull())
      {
        encryptionMaterial = Arrays.asList(
            mapper.treeToValue(rootNode, S3FileEncryptionMaterial[].class));
      }
    }
  }


  public enum CommandType
  {
    UPLOAD,
    DOWNLOAD
  }

  private CommandType commandType = CommandType.UPLOAD;

  private boolean autoCompress = true;

  private boolean overwrite = false;
  private int currentRowIndex;
  private List<Object> statusRows;

  private SnowflakeS3Client s3Client = null;

  private static final String SOURCE_COMPRESSION_AUTO_DETECT = "auto_detect";
  private static final String SOURCE_COMPRESSION_NONE = "none";

  private String sourceCompression = SOURCE_COMPRESSION_AUTO_DETECT;

  private ExecutorService threadExecutor = null;
  private Boolean canceled = false;

  /**
   * Result status enum
   */
  public enum ResultStatus
  {
    UNKNOWN("Unknown status"),
    UPLOADED("File uploaded"),
    UNSUPPORTED("File type not supported"),
    ERROR("Error encountered"),
    SKIPPED("Skipped since file exists"),
    NONEXIST("File does not exist"),
    COLLISION("File name collides with another file"),
    DIRECTORY("Not a file, but directory"),
    DOWNLOADED("File downloaded");

    private String desc;

    public String getDesc()
    {
      return desc;
    }

    private ResultStatus(String desc)
    {
      this.desc = desc;
    }
  }

  /**
   * S3 locations consists of a bucket name and path
   */
  private static class S3Location
  {
    String bucketName;
    String path;

    public S3Location(String s3BucketName, String s3path)
    {
      bucketName = s3BucketName;
      path = s3path;
    }
  }

  /**
   * A class for encapsulating the columns to return for the upload command
   */
  public enum UploadColumns
  {

    source, target, source_size, target_size,
    source_compression, target_compression, status, encryption, message

  };

  public class UploadCommandFacade
  {

    @FixedViewColumn(name = "source", ordinal = 10)
    private String srcFile;

    @FixedViewColumn(name = "target", ordinal = 20)
    private String destFile;

    @FixedViewColumn(name = "source_size", ordinal = 30)
    private long srcSize;

    @FixedViewColumn(name = "target_size", ordinal = 40)
    private long destSize = -1;

    @FixedViewColumn(name = "source_compression", ordinal = 50)
    private String srcCompressionType;

    @FixedViewColumn(name = "target_compression", ordinal = 60)
    private String destCompressionType;

    @FixedViewColumn(name = "status", ordinal = 70)
    private String resultStatus;

    @FixedViewColumn(name = "message", ordinal = 80)
    private String errorDetails;

    public UploadCommandFacade(String srcFile, String destFile,
                               String resultStatus,
                               String errorDetails,
                               long srcSize, long destSize,
                               String srcCompressionType,
                               String destCompressionType)
    {
      this.srcFile = srcFile;
      this.destFile = destFile;
      this.resultStatus = resultStatus;
      this.errorDetails = errorDetails;
      this.srcSize = srcSize;
      this.destSize = destSize;
      this.srcCompressionType = srcCompressionType;
      this.destCompressionType = destCompressionType;
    }
  }

  public class UploadCommandEncryptionFacade extends UploadCommandFacade
  {
    @FixedViewColumn(name = "encryption", ordinal = 75)
    private String encryption;

    public UploadCommandEncryptionFacade(String srcFile, String destFile,
                               String resultStatus,
                               String errorDetails,
                               long srcSize, long destSize,
                               String srcCompressionType,
                               String destCompressionType,
                               boolean isEncrypted)
    {
      super(srcFile, destFile, resultStatus, errorDetails, srcSize, destSize,
            srcCompressionType, destCompressionType);
      this.encryption = isEncrypted ? "ENCRYPTED" : "";
    }
  }

  /**
   * A class for encapsulating the columns to return for the download command
   */
  public class DownloadCommandFacade
  {
    @FixedViewColumn(name = "file", ordinal = 10)
    private String file;

    @FixedViewColumn(name = "size", ordinal = 20)
    private long size;

    @FixedViewColumn(name = "status", ordinal = 30)
    private String resultStatus;

    @FixedViewColumn(name = "message", ordinal = 40)
    private String errorDetails;

    public DownloadCommandFacade(String file,
                             String resultStatus,
                             String errorDetails,
                             long size)
    {
      this.file = file;
      this.resultStatus = resultStatus;
      this.errorDetails = errorDetails;
      this.size = size;
    }
  }

  public class DownloadCommandEncryptionFacade extends DownloadCommandFacade
  {
    @FixedViewColumn(name = "encryption", ordinal = 35)
    private String encryption;

    public DownloadCommandEncryptionFacade(String file,
                             String resultStatus,
                             String errorDetails,
                             long size,
                             boolean isEncrypted)
    {
      super(file, resultStatus, errorDetails, size);
      this.encryption = isEncrypted ? "DECRYPTED" : "";
    }
  }

  /**
   * File metadata with everything we care so we don't need to repeat
   * same processing to get these info.
   */
  private class FileMetadata
  {
    public String srcFileName;
    public long   srcFileSize;
    public String destFileName;
    public long destFileSize;
    public boolean requireCompress;
    public ResultStatus resultStatus = ResultStatus.UNKNOWN;
    public String errorDetails = "";
    public FileCompressionType srcCompressionType;
    public FileCompressionType destCompressionType;
    public boolean isEncrypted = false;
  }

  public enum FileCompressionType
  {
    GZIP(".gz", "application",
            Arrays.asList("gzip", "x-gzip"), true),
    DEFLATE(".deflate", "application",
            Arrays.asList("zlib", "deflate"), true),
    RAW_DEFLATE(".raw_deflate", "application",
            Arrays.asList("raw_deflate"), true),
    BZIP2(".bz2", "application",
            Arrays.asList("bzip2", "x-bzip2", "x-bz2", "x-bzip", "bz2"), true),
    LZIP(".lz", "application",
            Arrays.asList("lzip", "x-lzip"), false),
    LZMA(".lzma", "application",
            Arrays.asList("lzma", "x-lzma"), false),
    LZO(".lzo", "application",
            Arrays.asList("lzop", "x-lzop"), false),
    XZ(".xz", "application",
            Arrays.asList("xz", "x-xz"), false),
    COMPRESS(".Z", "application",
            Arrays.asList("compress", "x-compress"), false),
    PARQUET(".parquet", "snowflake",
            Collections.singletonList("parquet"), true);

    private FileCompressionType(String fileExtension, String mimeType,
                     List<String> mimeSubTypes,
                     boolean isSupported)
    {
      this.fileExtension = fileExtension;
      this.mimeType = mimeType;
      this.mimeSubTypes = mimeSubTypes;
      this.supported = isSupported;
    }

    private String fileExtension;
    private String mimeType;
    private List<String> mimeSubTypes;
    private boolean supported;

    static final Map<String, FileCompressionType> mimeSubTypeToCompressionMap =
     new HashMap<String, FileCompressionType>();

    static {
      for(FileCompressionType compression : FileCompressionType.values())
      {
        for(String mimeSubType : compression.mimeSubTypes)
        {
          mimeSubTypeToCompressionMap.put(mimeSubType, compression);
        }
      }
    }

    static public FileCompressionType lookupByMimeSubType(String mimeSubType)
    {
      return mimeSubTypeToCompressionMap.get(mimeSubType);
    }

    public boolean isSupported()
    {
      return supported;
    }
  }

  static class InputStreamWithMetadata
  {
    long size;
    String digest;

    // FileBackedOutputStream that should be destroyed when
    // the input stream has been consumed entirely
    FileBackedOutputStream fileBackedOutputStream;

    InputStreamWithMetadata(long size, String digest,
                            FileBackedOutputStream fileBackedOutputStream)
    {
      this.size = size;
      this.digest = digest;
      this.fileBackedOutputStream = fileBackedOutputStream;
    }
  }

  /**
   * Compress an input stream with GZIP and return the result size, digest and
   * compressed stream.
   *
   * @param inputStream data input
   * @return result size, digest and compressed stream
   * @throws SnowflakeSQLException if encountered exception when compressing
   */
  private static InputStreamWithMetadata compressStreamWithGZIP(
          InputStream inputStream) throws SnowflakeSQLException
  {
    FileBackedOutputStream tempStream =
          new FileBackedOutputStream(MAX_BUFFER_SIZE, true);

    try
    {

      DigestOutputStream digestStream = new DigestOutputStream(tempStream,
          MessageDigest.getInstance("SHA-256"));

      CountingOutputStream countingStream =
            new CountingOutputStream(digestStream);

      // construct a gzip stream with sync_flush mode
      GZIPOutputStream gzipStream;

      gzipStream = new GZIPOutputStream(countingStream, true);

      IOUtils.copy(inputStream, gzipStream);

      inputStream.close();

      gzipStream.finish();
      gzipStream.flush();

      countingStream.flush();

      return new InputStreamWithMetadata(countingStream.getCount(),
          Base64.encodeAsString(digestStream.getMessageDigest().digest()),
          tempStream);

    }
    catch (IOException | NoSuchAlgorithmException ex)
    {
      logger.error("Exception compressing input stream", ex);

      throw new SnowflakeSQLException(ex, SqlState.INTERNAL_ERROR,
              ErrorCode.INTERNAL_ERROR.getMessageCode(),
              "error encountered for compression");
    }

  }

    /**
   * Compress an input stream with GZIP and return the result size, digest and
   * compressed stream.
   *
   * @param inputStream
   * @return
   * @throws SnowflakeSQLException
   * @deprecated Can be removed when all accounts are encrypted
   */
  private static InputStreamWithMetadata compressStreamWithGZIPNoDigest(
          InputStream inputStream) throws SnowflakeSQLException
  {
    try
    {
      FileBackedOutputStream tempStream =
          new FileBackedOutputStream(MAX_BUFFER_SIZE, true);

      CountingOutputStream countingStream =
            new CountingOutputStream(tempStream);

      // construct a gzip stream with sync_flush mode
      GZIPOutputStream gzipStream;

      gzipStream = new GZIPOutputStream(countingStream, true);

      IOUtils.copy(inputStream, gzipStream);

      inputStream.close();

      gzipStream.finish();
      gzipStream.flush();

      countingStream.flush();

      return new InputStreamWithMetadata(countingStream.getCount(),
              null, tempStream);

    }
    catch (IOException ex)
    {
      logger.error("Exception compressing input stream", ex);

      throw new SnowflakeSQLException(ex, SqlState.INTERNAL_ERROR,
              ErrorCode.INTERNAL_ERROR.getMessageCode(),
              "error encountered for compression");
    }

  }


  private static InputStreamWithMetadata computeDigest(InputStream is,
                                                       boolean resetStream)
          throws NoSuchAlgorithmException, IOException
  {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    if (resetStream)
    {
      FileBackedOutputStream tempStream =
            new FileBackedOutputStream(MAX_BUFFER_SIZE, true);

      DigestOutputStream digestStream = new DigestOutputStream(tempStream, md);

      IOUtils.copy(is, digestStream);

      return new InputStreamWithMetadata(-1,
              Base64.encodeAsString(digestStream.getMessageDigest().digest()),
              tempStream);
    }
    else
    {
      DigestOutputStream digestStream = new DigestOutputStream(
                                            ByteStreams.nullOutputStream(),
                                            md);
      IOUtils.copy(is, digestStream);
      return new InputStreamWithMetadata(-1,
          Base64.encodeAsString(digestStream.getMessageDigest().digest()), null);
    }
  }

  /**
   * A callable that can be executed in a separate thread using exeuctor service.
   *
   * The callable does compression if needed and upload the result to the
   * table's staging area.
   *
   * @param stageLocationType stage location type
   * @param stageLocation stage location
   * @param srcFilePath source file path
   * @param requireCompress boolean indicating if requiring compression
   * @param fileMetadataMap map where key is file name and value is metadata
   * @param s3Client client object used to communicate with c3
   * @param connection connection object
   * @param command command string
   * @param inputStream null if upload source is file
   * @param sourceFromStream whether upload source is file or stream
   * @param sourceDataSize upload data size
   * @param parallel number of threads for parallel uploading
   * @param srcFile source file name
   * @param encMat not null if encryption is required
   * @return a callable that uploading file to s3
   */
  public static Callable<Void> getUploadFileCallable(
          final String stageLocationType,
          final String stageLocation,
          final String srcFilePath,
          final boolean requireCompress,
          final Map<String, FileMetadata> fileMetadataMap,
          final SnowflakeS3Client s3Client,
          final SFSession connection,
          final String command,
          final InputStream inputStream,
          final boolean sourceFromStream,
          final long sourceDataSize,
          final int parallel,
          final File srcFile,
          final S3FileEncryptionMaterial encMat,
          final String stageRegion) {
    return new Callable<Void>() {
      public Void call() throws Exception {

        logger.debug("Entering getUploadFileCallable...");

        FileMetadata metadata = fileMetadataMap.get(srcFilePath);

        InputStream uploadStream = inputStream;

        File fileToUpload = null;

        if (inputStream == null)
        {
          try
          {
            uploadStream = new FileInputStream(srcFilePath);
          }
          catch (FileNotFoundException ex)
          {
            metadata.resultStatus = ResultStatus.ERROR;
            metadata.errorDetails = ex.getMessage();
            throw ex;
          }
        }

        // this shouldn't happen
        if (metadata == null)
        {
          throw new SnowflakeSQLException(SqlState.INTERNAL_ERROR,
                  ErrorCode.INTERNAL_ERROR.getMessageCode(),
                  "missing file metadata for: " +  srcFilePath);
        }

        String destFileName = metadata.destFileName;

        long uploadSize = sourceDataSize;

        String digest = null;

        logger.debug("Dest file name={}, orig size={}",  destFileName, 
             uploadSize);

        // Temp file that needs to be cleaned up when upload was successful
        FileBackedOutputStream fileBackedOutputStream = null;

        // SNOW-16082: we should catpure exception if we fail to compress or
        // calcuate digest.
        try
        {
          if (requireCompress)
          {
            InputStreamWithMetadata compressedSizeAndStream = (encMat == null ?
              compressStreamWithGZIPNoDigest(uploadStream) :
              compressStreamWithGZIP(uploadStream));

            fileBackedOutputStream =
            compressedSizeAndStream.fileBackedOutputStream;

            // update the size
            uploadSize = compressedSizeAndStream.size;
            digest = compressedSizeAndStream.digest;

            if (compressedSizeAndStream.fileBackedOutputStream.getFile() != null)
              fileToUpload =
            compressedSizeAndStream.fileBackedOutputStream.getFile();

            logger.debug("New size after compression: {}", uploadSize);
          }
          else if (encMat != null)
          {
            // If encryption is on, we store our digest in the metadata
            InputStreamWithMetadata result = computeDigest(uploadStream,
                                                           sourceFromStream);
            digest = result.digest;
            fileBackedOutputStream = result.fileBackedOutputStream;

            if (!sourceFromStream)
              fileToUpload = srcFile;
            else if (result.fileBackedOutputStream.getFile() != null)
              fileToUpload = result.fileBackedOutputStream.getFile();
          }
          else
          {
            if (!sourceFromStream && (srcFile !=null))
              fileToUpload = srcFile;
          }

          logger.debug("Started copying file from: {} to {}:{} destName: {} " +
              "auto compressed? {} size={}", srcFilePath, stageLocationType, stageLocation,
                destFileName, (requireCompress ? "yes":"no"), uploadSize);

            // Simulated failure code.
          if (connection.getInjectFileUploadFailure()
              != null && srcFilePath.endsWith(
              (connection).getInjectFileUploadFailure()))
          {
            throw new SnowflakeSimulatedUploadFailure(
                    srcFile != null ? srcFile.getName() : "Unknown");
          }

          // upload it
          if ("LOCAL_FS".equalsIgnoreCase(stageLocationType))
          {
            pushFileToLocal(stageLocation,
                     srcFilePath, destFileName, uploadStream,
                     fileBackedOutputStream);
          }
          else if ("S3".equalsIgnoreCase(stageLocationType))
          {
            pushFileToS3(stageLocation, srcFilePath, destFileName,
                         uploadStream, fileBackedOutputStream, uploadSize,
                         digest, metadata.destCompressionType,
                         s3Client, connection, command, parallel, fileToUpload,
                         (fileToUpload == null), encMat, stageRegion);
            metadata.isEncrypted = encMat != null;
          }
          else
          {
            throw new SnowflakeSQLException(SqlState.INTERNAL_ERROR,
                    ErrorCode.INTERNAL_ERROR.getMessageCode(),
                    "Unknown stage type: " + stageLocationType);
          }
        }
        catch(SnowflakeSimulatedUploadFailure ex)
        {
          // This code path is used for Simulated failure code in tests.
          // Never happen in production
          metadata.resultStatus = ResultStatus.ERROR;
          metadata.errorDetails = ex.getMessage();
          throw ex;
        }
        catch(Throwable ex)
        {
          logger.error(
                     "Exception encountered during file upload", ex);
          metadata.resultStatus = ResultStatus.ERROR;
          metadata.errorDetails = ex.getMessage();
          throw ex;
        }
        finally
        {
          if (fileBackedOutputStream != null)
          {
            try
            {
              fileBackedOutputStream.reset();
            }
            catch (IOException ex)
            {
              logger.warn("failed to clean up temp file: {}", ex);
            }
          }
          if (inputStream == null)
          {
            IOUtils.closeQuietly(uploadStream);
          }
        }


        logger.debug("filePath: {}",  srcFilePath);

        // set dest size
        metadata.destFileSize = uploadSize;

        // mark the file as being uploaded
        metadata.resultStatus = ResultStatus.UPLOADED;

        return null;
      }
    };
  }

  /**
   * A callable that can be executed in a separate thread using exeuctor service.
   *
   * The callable download files from a stage location to a local location
   *
   * @param stageLocationType stage location type
   * @param stageLocation stage location
   * @param credentials credentials
   * @param srcFilePath path that stores the downloaded file
   * @param localLocation local location
   * @param fileMetadataMap file metadata map
   * @param s3Client s3 client
   * @param connection connection object
   * @param command command string
   * @param encMat s3 encryption material
   * @param parallel number of parallel threads for downloading
   * @return a callable responsible for downloading files
   */
  public static Callable<Void> getDownloadFileCallable(
          final String stageLocationType,
          final String stageLocation,
          final Map credentials,
          final String srcFilePath,
          final String localLocation,
          final Map<String, FileMetadata> fileMetadataMap,
          final SnowflakeS3Client s3Client,
          final SFSession connection,
          final String command,
          final int parallel,
          final S3FileEncryptionMaterial encMat,
          final String stageRegion) {
    return new Callable<Void>() {
      public Void call() throws Exception {

        logger.debug("Entering getDownloadFileCallable...");

        FileMetadata metadata = fileMetadataMap.get(srcFilePath);

        // this shouldn't happen
        if (metadata == null)
        {
          throw new SnowflakeSQLException(SqlState.INTERNAL_ERROR,
                  ErrorCode.INTERNAL_ERROR.getMessageCode(),
                  "missing file metadata for: " +  srcFilePath);
        }

        String destFileName = metadata.destFileName;
        logger.debug("Started copying file from: {}:{} file path:{} to {} destName:{}",
               stageLocationType, stageLocation, srcFilePath, localLocation, destFileName);

        try
        {
          // download it
          if ("LOCAL_FS".equalsIgnoreCase(stageLocationType))
          {
            pullFileFromLocal(stageLocation,
                              srcFilePath,
                              localLocation,
                              destFileName);
          }
          else if ("S3".equalsIgnoreCase(stageLocationType))
          {
            pullFileFromS3(stageLocation,
                           srcFilePath,
                           destFileName,
                           localLocation,
                           s3Client,
                           connection,
                           command,
                           parallel,
                           encMat,
                           stageRegion);
            metadata.isEncrypted = encMat != null;
          }
          else
          {
            throw new SnowflakeSQLException(SqlState.INTERNAL_ERROR,
                    ErrorCode.INTERNAL_ERROR.getMessageCode(),
                    "Unknown stage type: " + stageLocationType);
          }
        }
        catch(Throwable ex)
        {
          logger.error(
                     "Exception encountered during file download", ex);

          metadata.resultStatus = ResultStatus.ERROR;
          metadata.errorDetails = ex.getMessage();
          throw ex;
        }

        logger.debug("filePath: {}", srcFilePath);

        File destFile = new File(localLocation + localFSFileSep + destFileName);
        long downloadSize = destFile.length();

        // set dest size
        metadata.destFileSize = downloadSize;

        // mark the file as being uploaded
        metadata.resultStatus = ResultStatus.DOWNLOADED;

        return null;
      }
    };
  }

  public SnowflakeFileTransferAgent(String command,
                               SFSession connection,
                               SFStatement statement)
          throws SnowflakeSQLException
  {
    this.command = command;
    this.connection = connection;
    this.statement = statement;

    // parse the command
    logger.debug("Start parsing");

    parseCommand();

    if ("S3".equalsIgnoreCase(stageLocationType))
    {
      s3Client = createS3Client(stageCredentials, parallel, null, stageRegion);
    }
  }

  /**
   * Parse the put/get command.
   *
   * We send the command to the GS to do the parsing. In the future, we
   * will delegate more work to GS such as copying files from HTTP to S3.
   *
   * @throws SQLException
   */
  private void parseCommand() throws SnowflakeSQLException
  {
    JsonNode jsonNode = parseCommandInGS(statement, command);

    // get command type
    if (!jsonNode.path("data").path("command").isMissingNode())
      commandType = CommandType.valueOf(
              jsonNode.path("data").path("command").asText());

    // get source file locations as array (apply to both upload and download)
    JsonNode locationsNode = jsonNode.path("data").path("src_locations");

    assert locationsNode.isArray();

    String[] src_locations;

    try
    {
      src_locations = mapper.readValue(locationsNode.toString(), String[].class);
      initEncryptionMaterial(commandType, jsonNode);
    }
    catch (Exception ex)
    {
      throw new SnowflakeSQLException(ex, SqlState.INTERNAL_ERROR,
              ErrorCode.INTERNAL_ERROR.getMessageCode(),
              "Failed to parse the locations due to: " + ex.getMessage());
    }

    showEncryptionParameter = jsonNode.path("data")
                                      .path("clientShowEncryptionParameter")
                                      .asBoolean();

    String localFilePathFromGS = null;

    // do upload command specific parsing
    if (commandType == CommandType.UPLOAD)
    {
      if (src_locations.length > 0)
        localFilePathFromGS = src_locations[0];

      sourceFiles = expandFileNames(src_locations);

      autoCompress =
      jsonNode.path("data").path("autoCompress").asBoolean(true);

      if (!jsonNode.path("data").path("sourceCompression").isMissingNode())
        sourceCompression =
        jsonNode.path("data").path("sourceCompression").asText();

    }
    else
    {
      // do download command specific parsing
      srcFileToEncMat = new HashMap();

      // create mapping from source file to encryption materials
      if (src_locations.length == encryptionMaterial.size())
      {
        for (int srcFileIdx = 0; srcFileIdx < src_locations.length; srcFileIdx++)
        {
          srcFileToEncMat.put(src_locations[srcFileIdx],
                              encryptionMaterial.get(srcFileIdx));
        }
      }

      sourceFiles = new HashSet<String>(Arrays.asList(src_locations));

      localLocation = jsonNode.path("data").path("localLocation").asText();

      localFilePathFromGS = localLocation;

      if (localLocation.startsWith("~"))
      {
        // replace ~ with user home
        localLocation = System.getProperty("user.home") +
                        localLocation.substring(1);
      }

      // it should not contain any ~ after the above replacement
      if (localLocation.contains("~"))
      {
        throw new SnowflakeSQLException(SqlState.IO_ERROR,
                ErrorCode.PATH_NOT_DIRECTORY.getMessageCode(),
                localLocation);
      }

      // todo: replace ~userid with the home director of a given userid
      // one idea is to get the home directory for current user and replace
      // the last user id with the given user id.

      // user may also specify files relative to current directory
      // add the current path if that is the case
      if (!(new File(localLocation)).isAbsolute())
      {
        String cwd = System.getProperty("user.dir");

        logger.debug("Adding current working dir to relative file path.");

        localLocation = cwd + localFSFileSep + localLocation;
      }

      // local location should be a directory
      if ((new File(localLocation)).isFile())
        throw new SnowflakeSQLException(SqlState.IO_ERROR,
                ErrorCode.PATH_NOT_DIRECTORY.getMessageCode(), localLocation);
    }

    // SNOW-15153: verify that the value after file:// is not changed by GS
    verifyLocalFilePath(localFilePathFromGS);

    // more parameters common to upload/download
    stageLocation = jsonNode.path("data").path("stageInfo").
                    path("location").asText();

    parallel = jsonNode.path("data").path("parallel").asInt();

    overwrite = jsonNode.path("data").path("overwrite").asBoolean(false);

    stageLocationType = jsonNode.path("data").path("stageInfo").
                        path("locationType").asText();

    if (!jsonNode.path("data").path("stageInfo").path("region").isMissingNode())
    {
      stageRegion = jsonNode.path("data").path("stageInfo")
          .path("region").asText();
    }

    if ("LOCAL_FS".equalsIgnoreCase(stageLocationType))
    {
      if (stageLocation.startsWith("~"))
      {
        // replace ~ with user home
        stageLocation = System.getProperty("user.home") +
                        stageLocation.substring(1);
      }

      if (!(new File(stageLocation)).isAbsolute())
      {
        String cwd = System.getProperty("user.dir");

        logger.debug("Adding current working dir to stage file path.");

        stageLocation = cwd + localFSFileSep + stageLocation;
      }

    }

    if (logger.isDebugEnabled())
    {
      logger.debug("Command type: {}", commandType);

      if (commandType == CommandType.UPLOAD)
      {
        logger.debug("autoCompress: {}", autoCompress);

        logger.debug("source compression: {}", sourceCompression);
      }
      else
      {
        logger.debug("local download location: {}", localLocation);
      }

      logger.debug("Source files:");
      for (String srcFile : sourceFiles)
      {
        logger.debug("file: {}", srcFile);
      }

      logger.debug("stageLocation: {}", stageLocation);

      logger.debug("parallel: {}", parallel);

      logger.debug("overwrite: {}", overwrite);

      logger.debug("destLocationType: {}", stageLocationType);

      logger.debug("stageRegion: {}", stageRegion);
    }

    stageCredentials = extractStageCreds(jsonNode);
  }

  /**
   * A helper method to verify if the local file path from GS matches
   * what's parsed locally. This is for security purpose as documented in
   * SNOW-15153.
   *
   * @param localFilePathFromGS
   * @throws SnowflakeSQLException
   */
  private void verifyLocalFilePath(String localFilePathFromGS)
      throws SnowflakeSQLException
  {
    if (command == null)
    {
      logger.error("null command");
      return;
    }

    if (command.indexOf(FILE_PROTOCOL) < 0)
    {
      logger.error(
          "file:// prefix not found in command: {}", command);
      return;
    }

    int localFilePathBeginIdx = command.indexOf(FILE_PROTOCOL) +
        FILE_PROTOCOL.length();
    boolean isLocalFilePathQuoted =
        (localFilePathBeginIdx > FILE_PROTOCOL.length()) &&
        (command.charAt(localFilePathBeginIdx-1-FILE_PROTOCOL.length()) == '\'');

    // the ending index is exclusive
    int localFilePathEndIdx = 0;
    String localFilePath = "";

    if (isLocalFilePathQuoted)
    {
      // look for the matching quote
      localFilePathEndIdx = command.indexOf("'", localFilePathBeginIdx);
      if (localFilePathEndIdx > localFilePathBeginIdx)
        localFilePath = command.substring(localFilePathBeginIdx,
            localFilePathEndIdx);
      // unescape backslashes to match the file name from GS
      localFilePath = localFilePath.replaceAll("\\\\\\\\", "\\\\");
    }
    else
    {
      // look for the first space or new line or semi colon
      List<Integer> indexList = new ArrayList<>();
      char[] delimiterChars = {' ', '\n', ';'};
      for (int i=0; i<delimiterChars.length; i++)
      {
        int charIndex = command.indexOf(delimiterChars[i], localFilePathBeginIdx);
        if (charIndex != -1)
        {
          indexList.add(charIndex);
        }
      }

      localFilePathEndIdx = indexList.isEmpty() ? -1 : Collections.min(indexList);

      if (localFilePathEndIdx > localFilePathBeginIdx)
        localFilePath = command.substring(localFilePathBeginIdx,
            localFilePathEndIdx);
      else if (localFilePathEndIdx == -1)
      {
        localFilePath = command.substring(localFilePathBeginIdx);
      }
    }

    if (!localFilePath.isEmpty() && !localFilePath.equals(localFilePathFromGS))
    {
      throw new SnowflakeSQLException(SqlState.INTERNAL_ERROR,
          ErrorCode.INTERNAL_ERROR.getMessageCode(),
          "Unexpected local file path from GS. From GS: " +
              localFilePathFromGS + ", expected: " + localFilePath);
    }
    else if (localFilePath.isEmpty())
    {
      logger.warn(
          "fail to parse local file path from command: {}", command);
    }
    else
      logger.trace(
          "local file path from GS matches local parsing: {}", localFilePath);
  }

  /**
   * @return
   * @throws SnowflakeSQLException
   */
  private static JsonNode parseCommandInGS(SFStatement statement,
                                           String command)
      throws SnowflakeSQLException
  {
    Object result = null;
      // send the command to GS
    try
    {
      result = statement.executeHelper(command,
          "application/json", null, false);
    }
    catch (SFException ex)
    {
      throw new SnowflakeSQLException(ex, ex.getSqlState(),
          ex.getVendorCode(), ex.getParams());
    }

    JsonNode jsonNode = (JsonNode)result;
    logger.debug("response: {}", jsonNode.toString());

    SnowflakeUtil.checkErrorAndThrowException(jsonNode);
    return jsonNode;
  }

  /**
   * @param rootNode
   * @throws SnowflakeSQLException
   */
  private static Map extractStageCreds(JsonNode rootNode)
                                                   throws SnowflakeSQLException
  {
    JsonNode credsNode = rootNode.path("data").path("stageInfo").path("creds");
    Map stageCredentials = null;

    try
    {
      TypeReference<HashMap<String, String>> typeRef
      = new TypeReference<HashMap<String, String>>() {};
      stageCredentials = mapper.readValue(credsNode.toString(), typeRef);

    }
    catch (Exception ex)
    {
      throw new SnowflakeSQLException(ex, SqlState.INTERNAL_ERROR,
              ErrorCode.INTERNAL_ERROR.getMessageCode(),
              "Failed to parse the credentials (" +
              (credsNode!=null?credsNode.toString():"null") +
              ") due to exception: " + ex.getMessage());
    }

    if (logger.isDebugEnabled())
    {
      logger.debug("aws id: {}", stageCredentials.get("AWS_ID"));
      logger.debug("aws key: {}", stageCredentials.get("AWS_KEY"));
      logger.debug("aws token: {}", stageCredentials.get("AWS_TOKEN"));
    }

    return stageCredentials;
  }

  public boolean execute() throws SQLException
  {
    try
    {
      logger.debug("Start init metadata");

      // initialize file metadata map
      initFileMetadata();

      logger.debug("Start checking file types");

      // check file compression type
      if (commandType == CommandType.UPLOAD)
        processFileCompressionTypes();

      // filter out files that are already existing in the destination
      if (!overwrite)
      {
        logger.debug("Start filtering");

        filterExistingFiles();
      }

      synchronized (canceled)
      {
        if (canceled)
        {
          logger.debug("File transfer canceled by user");
          threadExecutor = null;
          return false;
        }
      }

      // create target directory for download command
      if (commandType == CommandType.DOWNLOAD)
      {
        File dir = new File(localLocation);
        if (!dir.exists())
        {
          boolean created = dir.mkdirs();

          if (created)
            logger.debug("directory created: {}", localLocation);
          else
            logger.debug("directory not created {}", localLocation);
        }

        downloadFiles();
      }
      else if (sourceFromStream)
        uploadStream();
      else
      {
        // separate files to big files list and small files list
        // big files will be uploaded in serial, while small files will be
        // uploaded concurrently.
        segregateFilesBySize();

        if (bigSourceFiles != null)
        {
          logger.debug("start uploading big files");
          uploadFiles(bigSourceFiles, 1);
          logger.debug("end uploading big files");
        }

        if (smallSourceFiles != null)
        {
          logger.debug("start uploading small files");
          uploadFiles(smallSourceFiles, parallel);
          logger.debug("end uploading small files");
        }
      }

      // create status rows to be returned to the client
      createStatusRows();

      return true;
    }
    finally
    {
      if (s3Client != null)
      {
        s3Client.shutdown();
      }
    }
  }

  /**
   * Helper to upload data from a stream
   */
  private void uploadStream() throws SnowflakeSQLException
  {
    try
    {
      threadExecutor = SnowflakeUtil.createDefaultExecutorService(
          "sf-stream-upload-worker-", 1);

      FileMetadata fileMetadata = fileMetadataMap.get(SRC_FILE_NAME_FOR_STREAM);

      S3FileEncryptionMaterial encMat = encryptionMaterial.get(0);
      if (commandType == CommandType.UPLOAD)
        threadExecutor.submit(getUploadFileCallable(
            stageLocationType, stageLocation, SRC_FILE_NAME_FOR_STREAM,
            compressSourceFromStream, fileMetadataMap,
            ("S3".equalsIgnoreCase(stageLocationType)) ?
               createS3Client(stageCredentials, parallel, encMat, stageRegion):null,
            connection, command,
            sourceStream, true, sourceStreamSize, parallel, null, encMat, stageRegion));
      else if (commandType == CommandType.DOWNLOAD)
        throw new SnowflakeSQLException(SqlState.INTERNAL_ERROR,
            ErrorCode.INTERNAL_ERROR.getMessageCode());

      threadExecutor.shutdown();

      try
      {
        // wait for all threads to complete without timeout
        threadExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
      }
      catch (InterruptedException ex)
      {
        throw new SnowflakeSQLException(SqlState.QUERY_CANCELED,
            ErrorCode.INTERRUPTED.getMessageCode());
      }
      logger.debug("Done with uploading from a stream");
    }
    finally
    {
      if (threadExecutor != null)
      {
        threadExecutor.shutdownNow();
        threadExecutor = null;
      }
    }
  }

  /**
   * Helper to download files from remote
   */
  private void downloadFiles() throws SnowflakeSQLException
  {
    try
    {
      threadExecutor = SnowflakeUtil.createDefaultExecutorService(
          "sf-file-download-worker-", 1);

      for (String srcFile : sourceFiles)
      {
        FileMetadata fileMetadata = fileMetadataMap.get(srcFile);

        // Check if the result status is already set so that we don't need to
        // upload it
        if (fileMetadata.resultStatus != ResultStatus.UNKNOWN)
        {
          logger.debug("Skipping {}, status: {}, details: {}",
              srcFile, fileMetadata.resultStatus, fileMetadata.errorDetails);
          continue;
        }

        S3FileEncryptionMaterial encMat = srcFileToEncMat.get(srcFile);
        threadExecutor.submit(getDownloadFileCallable(
            stageLocationType,
            stageLocation,
            stageCredentials,
            srcFile,
            localLocation,
            fileMetadataMap,
            ("S3".equalsIgnoreCase(stageLocationType)) ?
              createS3Client(stageCredentials, parallel, encMat, stageRegion):null,
            connection,
            command,
            parallel,
            encMat,
            stageRegion));

        logger.debug("submitted download job for: {}", srcFile);
      }

      threadExecutor.shutdown();

      try
      {
        // wait for all threads to complete without timeout
        threadExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
      }
      catch (InterruptedException ex)
      {
        throw new SnowflakeSQLException(SqlState.QUERY_CANCELED,
            ErrorCode.INTERRUPTED.getMessageCode());
      }
      logger.debug("Done with downloading");
    }
    finally
    {
      if (threadExecutor != null)
      {
        threadExecutor.shutdownNow();
        threadExecutor = null;
      }
    }
  }

  /**
   * This method create a thread pool based on requested number of threads
   * and upload the files using the thread pool.
   *
   * @param fileList
   * @param parallel
   * @throws SnowflakeSQLException
   */
  private void uploadFiles(Set<String> fileList,
                           int parallel) throws SnowflakeSQLException
  {
    try
    {
      threadExecutor = SnowflakeUtil.createDefaultExecutorService(
          "sf-file-upload-worker-", parallel);

      for (String srcFile : fileList)
      {
        FileMetadata fileMetadata = fileMetadataMap.get(srcFile);

        // Check if the result status is already set so that we don't need to
        // upload it
        if (fileMetadata.resultStatus != ResultStatus.UNKNOWN)
        {
          logger.debug("Skipping {}, status: {}, details: {}",
              srcFile, fileMetadata.resultStatus, fileMetadata.errorDetails);

          continue;
        }

        /**
         * For small files, we upload files in parallel, so we don't
         * want the s3 uploader to upload parts in parallel for each file.
         * For large files, we upload them in serial, and we want s3 uploader
         * to upload parts in parallel for each file. This is the reason
         * for the parallel value.
         */
        File srcFileObj = new File(srcFile);

        threadExecutor.submit(getUploadFileCallable(
            stageLocationType, stageLocation, srcFile,
            fileMetadata.requireCompress, fileMetadataMap,
            ("S3".equalsIgnoreCase(stageLocationType)) ?
               createS3Client(stageCredentials, parallel,encryptionMaterial.get(0), stageRegion):
               null,
            connection, command,
            null, false, srcFileObj.length(),
            (parallel > 1 ?  1: this.parallel), srcFileObj, encryptionMaterial.get(0), stageRegion));

        logger.debug("submitted copy job for: {}", srcFile);
      }

      // shut down the thread executor
      threadExecutor.shutdown();

      try
      {
        // wait for all threads to complete without timeout
        threadExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
      }
      catch (InterruptedException ex)
      {
        throw new SnowflakeSQLException(SqlState.QUERY_CANCELED,
            ErrorCode.INTERRUPTED.getMessageCode());
      }
      logger.debug("Done with uploading");

    }
    finally
    {
      // shut down the thread pool in any case
      if (threadExecutor != null)
      {
        threadExecutor.shutdownNow();
        threadExecutor = null;
      }
    }
  }

  private void segregateFilesBySize()
  {
    for(String srcFile : sourceFiles)
    {
      if ((new File(srcFile)).length() > BIG_FILE_THRESHOLD)
      {
        if (bigSourceFiles == null)
        {
          bigSourceFiles = new HashSet<String>(sourceFiles.size());
        }

        bigSourceFiles.add(srcFile);
      }
      else
      {
        if (smallSourceFiles == null)
        {
          smallSourceFiles = new HashSet<String>(sourceFiles.size());
        }

        smallSourceFiles.add(srcFile);
      }
    }
  }

  public void cancel()
  {
    synchronized (canceled)
    {
      if (threadExecutor != null)
      {
        threadExecutor.shutdownNow();
        threadExecutor = null;
      }
      canceled = true;

    }
  }

  /**
   * process a list of file paths separated by "," and expand the wildcards
   * if any to generate the list of paths for all files matched by the
   * wildcards
   *
   * @param filePathList file path list
   * @return a set of file names that is matched
   * @throws SnowflakeSQLException if cannot find the file
   */
  static public Set<String> expandFileNames(String[] filePathList)
          throws SnowflakeSQLException
  {
    Set<String> result = new HashSet<String>();

    // a location to file pattern map so that we only need to list the
    // same directory once when they appear in multiple times.
    Map<String, List<String>> locationToFilePatterns;

    locationToFilePatterns = new HashMap<String, List<String>>();

    String cwd = System.getProperty("user.dir");

    for (String path : filePathList)
    {
      // replace ~ with user home
      path = path.replace("~", System.getProperty("user.home"));

      // user may also specify files relative to current directory
      // add the current path if that is the case
      if (!(new File(path)).isAbsolute())
      {
        logger.debug("Adding current working dir to relative file path.");

        path = cwd + localFSFileSep + path;
      }

      // check if the path contains any wildcards
      if (!path.contains("*") && !path.contains("?") &&
          !(path.contains("[") && path.contains("]")))
      {
        /* this file path doesn't have any wildcard, so we don't need to
         * expand it
         */
        result.add(path);
      }
      else
      {
        // get the directory path
        int lastFileSepIndex = path.lastIndexOf(localFSFileSep);

        // SNOW-15203: if we don't find a default file sep, try "/" if it is not
        // the default file sep.
        if (lastFileSepIndex < 0 && !"/".equals(localFSFileSep))
        {
          lastFileSepIndex = path.lastIndexOf("/");
        }

        String loc = path.substring(0, lastFileSepIndex + 1);

        String filePattern = path.substring(lastFileSepIndex + 1);

        List<String> filePatterns = locationToFilePatterns.get(loc);

        if (filePatterns == null)
        {
          filePatterns = new ArrayList<String>();
          locationToFilePatterns.put(loc, filePatterns);
        }

        filePatterns.add(filePattern);
      }
    }

    // For each location, list files and match against the patterns
    for (Map.Entry<String, List<String>> entry :
            locationToFilePatterns.entrySet())
    {
      try
      {
        java.io.File dir = new java.io.File(entry.getKey());

        logger.debug("Listing files under: {} with patterns: {}",
            entry.getKey(), entry.getValue().toString());

        // The following currently ignore sub directories
        for (Object file : FileUtils.listFiles(dir,
                                             new WildcardFileFilter(entry.getValue()),
                                             null))
        {
          result.add(((java.io.File) file).getCanonicalPath());
        }
      }
      catch (Exception ex)
      {
        throw new SnowflakeSQLException(ex, SqlState.DATA_EXCEPTION,
                ErrorCode.FAIL_LIST_FILES.getMessageCode(),
                "Exception: " + ex.getMessage() + ", Dir=" +
                entry.getKey() + ", Patterns=" +
                entry.getValue().toString());
      }
    }

    logger.debug("Expanded file paths: ");

    for (String filePath : result)
    {
      logger.debug("file: {}", filePath);
    }

    return result;
  }

  static private boolean pushFileToLocal(String stageLocation,
                                         String filePath,
                                         String destFileName,
                                         InputStream inputStream,
                                         FileBackedOutputStream fileBackedOutStr)
          throws SQLException
  {


    // replace ~ with user home
    stageLocation = stageLocation.replace("~",
                                            System.getProperty("user.home"));
    try
    {
      logger.debug("Copy file. srcFile={}, destination={}, destFileName={}",
                    filePath, stageLocation, destFileName);

      File destFile = new File(
              SnowflakeUtil.concatFilePathNames(
                      stageLocation,
                      destFileName,
                      localFSFileSep));

      if (fileBackedOutStr != null)
      {
        inputStream = fileBackedOutStr.asByteSource().openStream();
      }
      FileUtils.copyInputStreamToFile(inputStream, destFile);
    }
    catch (Exception ex)
    {
      throw new SnowflakeSQLException(ex, SqlState.INTERNAL_ERROR,
              ErrorCode.INTERNAL_ERROR.getMessageCode(),
              ex.getMessage());
    }

    return true;
  }

  static private boolean pullFileFromLocal(String sourceLocation,
                                           String filePath,
                                           String destLocation,
                                           String destFileName)
          throws SQLException
  {
    try
    {
      logger.debug("Copy file. srcFile={}, destination={}, destFileName={}",
                  sourceLocation + localFSFileSep + filePath, destLocation, destFileName);

      File srcFile = new File(
              SnowflakeUtil.concatFilePathNames(
                      sourceLocation,
                      filePath,
                      localFSFileSep));

      FileUtils.copyFileToDirectory(srcFile, new File(destLocation));
    }
    catch (Exception ex)
    {
      throw new SnowflakeSQLException(ex, SqlState.INTERNAL_ERROR,
              ErrorCode.INTERNAL_ERROR.getMessageCode(),
              ex.getMessage());
    }

    return true;
  }

  static private void pushFileToS3(String stageLocation,
                                      String filePath,
                                      String destFileName,
                                      InputStream inputStream,
                                      FileBackedOutputStream fileBackedOutStr,
                                      long uploadSize,
                                      String digest,
                                      FileCompressionType compressionType,
                                      SnowflakeS3Client initialS3Client,
                                      SFSession connection,
                                      String command,
                                      int parallel,
                                      File srcFile,
                                      boolean uploadFromStream,
                                      S3FileEncryptionMaterial encMat,
                                      String stageRegion)
      throws SQLException, IOException
  {
    S3Location s3Location = extractBucketNameAndPath(stageLocation);

    if (s3Location.path != null && !s3Location.path.isEmpty())
    {
      destFileName = s3Location.path +
                     (!s3Location.path.endsWith("/")?"/":"")
                     + destFileName;
    }
    if (logger.isDebugEnabled())
    {
      logger.debug("upload object. bucketName={}, key={}, srcFile={}, encryption={}",
          s3Location.bucketName, destFileName, filePath, (encMat == null ? "NULL" :
              Long.toString(encMat.getSmkId()) + "|" + encMat.getQueryId()));
    }

    ObjectMetadata meta = new ObjectMetadata();
    meta.setContentLength(uploadSize);
    if (digest != null)
    {
      meta.addUserMetadata("sfc-digest", digest);
    }

    if (compressionType != null &&
        compressionType.isSupported())
      meta.setContentEncoding(compressionType.name().toLowerCase());

    try
    {
      SnowflakeS3Client.upload(initialS3Client, connection, command, parallel,
          CLIENT_SIDE_MAX_RETRIES, uploadFromStream,
          s3Location.bucketName, srcFile, destFileName,
          inputStream, fileBackedOutStr, meta, stageRegion);
    }
    finally
    {
      if (uploadFromStream && inputStream != null)
        inputStream.close();
    }
  }

  /**
   * A helper method to handle exception when performing an S3 call:
   * list, put or get
   *
   * @param ex
   * @param retryCount
   * @param operation
   * @param encMat
   * @param connection
   * @param command
   * @param parallel
   * @param s3Client
   * @return
   * @throws SnowflakeSQLException
   */
  static private SnowflakeS3Client handleS3Exception(Exception ex,
                                 int retryCount,
                                 String operation,
                                 S3FileEncryptionMaterial encMat,
                                 SFSession connection,
                                 String command,
                                 int parallel,
                                 SnowflakeS3Client s3Client,
                                 String stageRegion)
      throws SnowflakeSQLException
  {
    // no need to retry if it is invalid key exception
    if (ex.getCause() instanceof InvalidKeyException)
    {
      // Most likely cause: Unlimited strength policy files not installed
      String msg = "Strong encryption with Java JRE requires JCE " +
          "Unlimited Strength Jurisdiction Policy files. " +
          "Follow JDBC client installation instructions " +
          "provided by Snowflake or contact Snowflake Support.";
      logger.error(
          "JCE Unlimited Strength policy files missing: {}. {}.",
          new Object[]{ex.getMessage(),
              ex.getCause().getMessage()});
      String bootLib =
          java.lang.System.getProperty("sun.boot.library.path");
      if (bootLib != null)
      {
        msg += " The target directory on your system is: " +
            Paths.get(bootLib,"security").toString();
        logger.error(msg);
      }
      throw new SnowflakeSQLException(ex, SqlState.SYSTEM_ERROR,
          ErrorCode.AWS_CLIENT_ERROR.getMessageCode(),
          operation, msg);
    }

    if (ex instanceof AmazonClientException)
    {
      if (retryCount > CLIENT_SIDE_MAX_RETRIES)
      {
        String extendedRequestId = "none";

        if (ex instanceof AmazonS3Exception)
        {
          AmazonS3Exception ex1 = (AmazonS3Exception) ex;
          extendedRequestId = ex1.getExtendedRequestId();
        }

        if (ex instanceof AmazonServiceException)
        {
          AmazonServiceException ex1 = (AmazonServiceException) ex;
          throw new SnowflakeSQLException(ex1, SqlState.SYSTEM_ERROR,
              ErrorCode.S3_OPERATION_ERROR.getMessageCode(),
              operation,
              ex1.getErrorType().toString(),
              ex1.getErrorCode(),
              ex1.getMessage(), ex1.getRequestId(),
              extendedRequestId);
        }
        else
          throw new SnowflakeSQLException(ex, SqlState.SYSTEM_ERROR,
              ErrorCode.AWS_CLIENT_ERROR.getMessageCode(),
              operation, ex.getMessage());
      }
      else
      {
        logger.debug("Encountered exception ({}) during " +
                operation + ", retry count: {}",
            new Object[]{ex.getMessage(), retryCount});
        logger.debug("Stack trace: ", ex);

        // exponential backoff up to a limit
        int backoffInMillis = CLIENT_SIDE_RETRY_BACKOFF_MIN;
        if (retryCount > 1)
          backoffInMillis <<= (Math.min(retryCount-1,
              CLIENT_SIDE_RETRY_BACKOFF_MAX_EXPONENT));

        try
        {
          logger.debug("Sleep for {} milliseconds before retry",
              backoffInMillis);

          Thread.sleep(backoffInMillis);
        }
        catch(InterruptedException ex1)
        {
          // ignore
        }

        return renewExpiredAWSToken(connection, command, parallel, s3Client,
                (AmazonClientException) ex, encMat, stageRegion);
      }
    }
    else
    {
      if (ex instanceof InterruptedException ||
          SnowflakeUtil.getRootCause(ex) instanceof SocketTimeoutException)
      {
        if (retryCount > CLIENT_SIDE_MAX_RETRIES)
          throw new SnowflakeSQLException(ex, SqlState.SYSTEM_ERROR,
              ErrorCode.IO_ERROR.getMessageCode(),
              "Encountered exception during " + operation +  ": " +
                  ex.getMessage());
        else
        {
          logger.debug("Encountered exception ({}) during " +
                  operation + ", retry count: {}",
              new Object[]{ex.getMessage(), retryCount});

          return s3Client;
        }
      }
      else
        throw new SnowflakeSQLException(ex, SqlState.SYSTEM_ERROR,
            ErrorCode.IO_ERROR.getMessageCode(),
            "Encountered exception during " + operation + ": " +
                ex.getMessage());
    }
  }

  /**
   * @param connection
   * @param command
   * @param parallel
   * @param s3Client
   * @param ex
   * @return
   * @throws SnowflakeSQLException
   */
  static
      SnowflakeS3Client
      renewExpiredAWSToken(SFSession connection, String command,
                           int parallel, SnowflakeS3Client s3Client,
                           AmazonClientException ex, S3FileEncryptionMaterial encMat,
                           String stageRegion)
                                                    throws SnowflakeSQLException
  {
    if (ex instanceof AmazonS3Exception)
    {
      AmazonS3Exception s3ex = (AmazonS3Exception) ex;
      if (s3ex.getErrorCode().equalsIgnoreCase(EXPIRED_AWS_TOKEN_ERROR_CODE))
      {
        SFStatement statement = new SFStatement(connection);
        JsonNode jsonNode = parseCommandInGS(statement, command);
        Map stageCredentials = extractStageCreds(jsonNode);
        s3Client = createS3Client(stageCredentials, parallel, encMat, stageRegion);
      }
    }
    return s3Client;
  }

  static private void pullFileFromS3(String stageLocation,
                                        String filePath,
                                        String destFileName,
                                        String localLocation,
                                        SnowflakeS3Client initialS3Client,
                                        SFSession connection,
                                        String command,
                                        int parallel,
                                        S3FileEncryptionMaterial encMat,
                                        String stageRegion)
          throws SQLException
  {
    S3Location s3Location = extractBucketNameAndPath(stageLocation);

    String stageFilePath = filePath;

    if (!s3Location.path.isEmpty())
    {
      stageFilePath = SnowflakeUtil.concatFilePathNames(s3Location.path,
                                                         filePath, "/");
    }
    if (logger.isDebugEnabled())
    {
      logger.debug("Download object. bucketName={}, key={}, srcFile={}, encryption={}",
          s3Location.bucketName, stageFilePath, filePath,
          (encMat == null ? "NULL" : Long.toString(encMat.getSmkId()) +
              "|" + encMat.getQueryId()));
    }

    SnowflakeS3Client.download(initialS3Client, connection, command,
                               localLocation, destFileName, parallel,
                               s3Location.bucketName, stageFilePath,stageRegion);
  }

  /**
   * From the set of files intended to be uploaded/downloaded, derive a common
   * prefix and use the listObjects API to get the object summary for each
   * object that has the common prefix.
   *
   * For each returned object, we compare the size and digest with the local file
   * and if they are the same, we will not upload/download the file.
   *
   * @throws SnowflakeSQLException
   */
  private void filterExistingFiles() throws SnowflakeSQLException
  {
    /*
     * Build a reverse map from destination file name to source file path
     * The map will be used for looking up the source file for destination
     * files that already exist in destination location and mark them to be
     * skipped for uploading/downloading
     */
    Map<String, String> destFileNameToSrcFileMap =
    new HashMap<String, String>(fileMetadataMap.size());

    logger.debug("Build reverse map from destination file name to source file");

    for (Map.Entry<String, FileMetadata> entry : fileMetadataMap.entrySet())
    {
      if (entry.getValue().destFileName != null)
      {
        String prevSrcFile =
        destFileNameToSrcFileMap.put(entry.getValue().destFileName,
                                     entry.getKey());

        if (prevSrcFile != null)
        {
          FileMetadata prevFileMetadata = fileMetadataMap.get(prevSrcFile);

          prevFileMetadata.resultStatus = ResultStatus.COLLISION;
          prevFileMetadata.errorDetails = prevSrcFile + " has same name as " +
                                          entry.getKey();
        }
      }
      else
      {
        logger.debug("No dest file name found for: {}", entry.getKey());
        logger.debug("Status: {}", entry.getValue().resultStatus);
      }
    }

    // no files to be processed
    if (destFileNameToSrcFileMap.size() == 0)
      return;

    // determine greatest common prefix for all stage file names so that
    // we can call s3 API to list the objects and get their digest to compare
    // with local files
    String[] stageFileNames;

    if (commandType == CommandType.UPLOAD)
      stageFileNames = destFileNameToSrcFileMap.keySet().toArray(new String[0]);
    else
      stageFileNames =
      destFileNameToSrcFileMap.values().toArray(new String[0]);

    // find greatest common prefix for all stage file names
    Arrays.sort(stageFileNames);

    String greatestCommonPrefix =
    SnowflakeUtil.greatestCommonPrefix(stageFileNames[0],
                                   stageFileNames[stageFileNames.length-1]);

    logger.debug("Greatest common prefix: {}", greatestCommonPrefix);

    // use the greatest common prefix to list objects under stage location
    if ("S3".equalsIgnoreCase(stageLocationType))
    {
      logger.debug("check existing files on s3 for the common prefix");

      S3Location s3Location = extractBucketNameAndPath(stageLocation);

      ObjectListing objList = null;

      int retryCount = 0;

      do
      {
        try
        {
          objList = s3Client.listObjects(s3Location.bucketName,
              SnowflakeUtil.concatFilePathNames(
                  s3Location.path,
                  greatestCommonPrefix, "/"));

          // exit retry loop
          break;
        }
        catch(Exception ex)
        {
          logger.warn("Listing objects for filtering encountered exception: {}",
              ex.getMessage());

          s3Client = handleS3Exception(ex, ++retryCount, "listObjects",
              null, connection, command, parallel, s3Client, stageRegion);
        }
      }
      while(retryCount <= CLIENT_SIDE_MAX_RETRIES);

      List<S3ObjectSummary> s3Objects = objList.getObjectSummaries();

      for (S3ObjectSummary obj : s3Objects)
      {
        logger.debug(
            "Existing object: key={} size={} etag={}",
            new Object[]{obj.getKey(), obj.getSize(), obj.getETag()});

        int idxOfLastFileSep = obj.getKey().lastIndexOf("/");
        String s3ObjFileName = obj.getKey().substring(idxOfLastFileSep + 1);

        // get the path to the local file so that we can calculate digest
        String mappedSrcFile = destFileNameToSrcFileMap.get(s3ObjFileName);

        // skip objects that don't have a corresponding file to be uploaded
        if (mappedSrcFile == null)
          continue;

        logger.debug("Next compare digest for {} against {} on S3", mappedSrcFile, s3ObjFileName);

        String localFile = null;
        final boolean remoteEncrypted;

        try
        {
          localFile = (commandType==CommandType.UPLOAD)?
                             mappedSrcFile:(localLocation + s3ObjFileName);

          if (commandType == CommandType.DOWNLOAD &&
              !(new File(localFile)).exists())
          {
            logger.debug("File does not exist locally, will download {}", 
                        mappedSrcFile);
            continue;
          }

          // Check file size first, if their difference is bigger than the block
          // size, we don't need to compare digests
          if (!fileMetadataMap.get(mappedSrcFile).requireCompress &&
              Math.abs(obj.getSize() - (new File(localFile)).length()) > 16)
          {
            logger.debug("Size diff between S3 and local, will {} {}", 
                        commandType.name().toLowerCase(), mappedSrcFile);
            continue;
          }

          // Get object metadata from S3

          ObjectMetadata meta;

          try
          {
            meta = s3Client.getObjectMetadata(obj.getBucketName(),
                obj.getKey());
          }
          catch(Exception ex)
          {
            if (ex instanceof AmazonS3Exception)
            {
              AmazonS3Exception s3Ex = (AmazonS3Exception)ex;
              // SNOW-14521: when file is not found, ok to upload
              if (s3Ex.getStatusCode() == 404)
              {
                // log it
                logger.warn("File returned from listing but found missing {} when getting its" +
                            " metadata. Bucket={}, key={}", 
                            obj.getBucketName(), obj.getKey());

                // the file is not found, ok to upload
                continue;
              }
            }

            // for any other exception, log an error
            logger.error("Fetching object metadata encountered exception: {}",
                   ex.getMessage());

            throw ex;
          }

          String objDigest = meta.getUserMetadata().get("sfc-digest");
          remoteEncrypted = MatDesc.parse(
              meta.getUserMetadata().get("x-amz-matdesc")) != null;

          // calculate the digest hash of the local file
          InputStream fileStream = null;
          String hashText = null;

          // Streams (potentially with temp files) to clean up
          final List<FileBackedOutputStream> fileBackedOutputStreams
              = new ArrayList();
          try
          {
            fileStream = new FileInputStream(localFile);
            if (fileMetadataMap.get(mappedSrcFile).requireCompress)
            {
              logger.debug("Compressing stream for digest check");

              InputStreamWithMetadata res = compressStreamWithGZIP(fileStream);

              fileStream =
                  res.fileBackedOutputStream.asByteSource().openStream();
              fileBackedOutputStreams.add(res.fileBackedOutputStream);
            }

            // If the remote file has our digest, compute the SHA-256
            // for the local file
            // If the remote file does not have our digest but is unencrypted,
            // we compare the MD5 of the unencrypted local file to the ETag
            // of the S3 file.
            // Otherwise (remote file is encrypted, but has no sfc-digest),
            // no comparison is performed
            if (objDigest != null)
            {
              InputStreamWithMetadata res = computeDigest(fileStream,false);
              hashText = res.digest;
              fileBackedOutputStreams.add(res.fileBackedOutputStream);

            }
            else if (!remoteEncrypted)
            {
              hashText = DigestUtils.md5Hex(fileStream);
            }
          }
          finally
          {
            if (fileStream != null)
            {
              fileStream.close();
            }

            for (FileBackedOutputStream stream : fileBackedOutputStreams)
            {
              if (stream != null)
              {
                try
                {
                  stream.reset();
                }
                catch (IOException ex)
                {
                  logger.warn("failed to clean up temp file: {}", ex);
                }
              }
            }
          }

          // continue so that we will upload the file
          if (hashText == null || // remote is encrypted & has no digest
              (objDigest != null && !hashText.equals(objDigest)) || // digest mismatch
              (objDigest == null && !hashText.equals(obj.getETag()))) // ETag/MD5 mismatch
          {
            logger.debug(
                "digest diff between S3 and local, will {} {}, " +
                    "local digest: {}, s3 digest: {}",
                new Object[]{commandType.name().toLowerCase(),
                    mappedSrcFile, hashText, obj.getETag()});

            continue;
          }
        }
        catch (IOException | NoSuchAlgorithmException ex)
        {
          throw new SnowflakeSQLException(ex, SqlState.INTERNAL_ERROR,
              ErrorCode.INTERNAL_ERROR.getMessageCode(),
              "Error reading: " + localFile);
        }

        logger.debug("digest same between S3 and local, will not upload {} {}",
                      commandType.name().toLowerCase(), mappedSrcFile);

        skipFile(mappedSrcFile, s3ObjFileName);
      }
    }
    else if ("LOCAL_FS".equalsIgnoreCase(stageLocationType))
    {
      for(String stageFileName : stageFileNames)
      {
        String stageFilePath =
        SnowflakeUtil.concatFilePathNames(stageLocation,
                                          stageFileName,
                                          localFSFileSep);

        File stageFile = new File(stageFilePath);

        // if stage file doesn't exist, no need to skip whether for
        // upload/download
        if (!stageFile.exists())
          continue;

        String mappedSrcFile = (commandType==CommandType.UPLOAD)?
                               destFileNameToSrcFileMap.get(stageFileName):
                               stageFileName;

        String localFile = (commandType==CommandType.UPLOAD) ?
                           mappedSrcFile :
                           (localLocation +
                            fileMetadataMap.get(mappedSrcFile).destFileName);

        // Check file size first, if they are different, we don't need
        // to check digest
        if (!fileMetadataMap.get(mappedSrcFile).requireCompress &&
            stageFile.length() != (new File(localFile)).length())
        {
          logger.debug("Size diff between stage and local, will {} {}",
                      commandType.name().toLowerCase(), mappedSrcFile);
          continue;
        }

        // stage file eixst and either we will be compressing or
        // the dest file has same size as the source file size we will
        // compare digest values below
        String localFileHashText = null;
        String stageFileHashText = null;

        List<FileBackedOutputStream> fileBackedOutputStreams = new ArrayList();
        InputStream localFileStream = null;
        try
        {
          // calculate the digest hash of the local file
          localFileStream = new FileInputStream(localFile);

          if (fileMetadataMap.get(mappedSrcFile).requireCompress)
          {
            logger.debug("Compressing stream for digest check");

            InputStreamWithMetadata res =
            compressStreamWithGZIP(localFileStream);
            fileBackedOutputStreams.add(res.fileBackedOutputStream);

            localFileStream =
                res.fileBackedOutputStream.asByteSource().openStream();
          }

          InputStreamWithMetadata res = computeDigest(localFileStream, false);
          localFileHashText = res.digest;
          fileBackedOutputStreams.add(res.fileBackedOutputStream);
        }
        catch (IOException | NoSuchAlgorithmException ex)
        {
          throw new SnowflakeSQLException(ex, SqlState.INTERNAL_ERROR,
                  ErrorCode.INTERNAL_ERROR.getMessageCode(),
                  "Error reading local file: " + localFile);
        }
        finally
        {
            for (FileBackedOutputStream stream : fileBackedOutputStreams)
            {
              if (stream != null)
              {
                try
                {
                  stream.reset();
                }
                catch (IOException ex)
                {
                  logger.warn("failed to clean up temp file: {}", ex);
                }
              }
            }
            IOUtils.closeQuietly(localFileStream);
        }

        FileBackedOutputStream fileBackedOutputStream = null;
        InputStream stageFileStream = null;
        try
        {
          // calculate digst for stage file
          stageFileStream = new FileInputStream(stageFilePath);

          InputStreamWithMetadata res = computeDigest(stageFileStream, false);
          stageFileHashText = res.digest;
          fileBackedOutputStream = res.fileBackedOutputStream;

        }
        catch (IOException | NoSuchAlgorithmException ex)
        {
          throw new SnowflakeSQLException(ex, SqlState.INTERNAL_ERROR,
                  ErrorCode.INTERNAL_ERROR.getMessageCode(),
                  "Error reading stage file: " + stageFilePath);
        }
        finally
        {
            try
            {
              if (fileBackedOutputStream != null)
              {
                fileBackedOutputStream.reset();
              }
            }
            catch (IOException ex)
            {
              logger.warn("failed to clean up temp file: {}", ex);
            }
            IOUtils.closeQuietly(stageFileStream);
        }

        // continue if digest is different so that we will process the file
        if (!stageFileHashText.equals(localFileHashText))
        {
          logger.debug("digest diff between local and stage, will {} {}",
                        commandType.name().toLowerCase(), mappedSrcFile);
          continue;
        }
        else
        {
          logger.debug("digest matches between local and stage, will skip {}", 
                      mappedSrcFile);

          // skip the file given that the check sum is the same b/w source
          // and destination
          skipFile(mappedSrcFile, stageFileName);
        }
      }
    }
    else
    {
      logger.error(
                 "Existing file check not supported for FS type={}",
                   stageLocationType);

      throw new SnowflakeSQLException(SqlState.INTERNAL_ERROR,
              ErrorCode.INTERNAL_ERROR.getMessageCode(),
              "Unsupported stage location type: " + stageLocationType);
    }
  }

  private void skipFile(String srcFilePath, String destFileName)
  {
    FileMetadata fileMetadata = fileMetadataMap.get(srcFilePath);

    if (fileMetadata != null)
    {
      if (fileMetadata.resultStatus == null ||
          fileMetadata.resultStatus == ResultStatus.UNKNOWN)
      {
        logger.debug("Mark {} as skipped", srcFilePath);

        fileMetadata.resultStatus = ResultStatus.SKIPPED;
        fileMetadata.errorDetails =
        "File with same destination name and checksum already exists: "
        + destFileName;
      }
      else
      {
        logger.debug("No need to mark as skipped for: {} status was already marked as: {}",
                      srcFilePath, fileMetadata.resultStatus);
      }
    }
  }

  private void initFileMetadata()
  throws SnowflakeSQLException
  {
    // file metadata is keyed on source file names (which are local file names
    // for upload command and stage file names for download command)
    fileMetadataMap = new HashMap<String, FileMetadata>(sourceFiles.size());

    if (commandType == CommandType.UPLOAD)
    {
      if (sourceFromStream)
      {
        FileMetadata fileMetadata = new FileMetadata();
        fileMetadataMap.put(SRC_FILE_NAME_FOR_STREAM, fileMetadata);
        fileMetadata.srcFileName = SRC_FILE_NAME_FOR_STREAM;
      }
      else
      {
        for (String sourceFile : sourceFiles)
        {
          FileMetadata fileMetadata = new FileMetadata();
          fileMetadataMap.put(sourceFile, fileMetadata);
          File file = new File(sourceFile);

          fileMetadata.srcFileName = file.getName();
          fileMetadata.srcFileSize = file.length();

          if (!file.exists())
          {
            logger.warn("File doesn't exist: {}", sourceFile);

            throw new SnowflakeSQLException(SqlState.DATA_EXCEPTION,
                ErrorCode.FILE_NOT_FOUND.getMessageCode(),
                sourceFile);
          } else if (file.isDirectory())
          {
            logger.warn("Not a file, but directory: {}", sourceFile);

            throw new SnowflakeSQLException(SqlState.DATA_EXCEPTION,
                ErrorCode.FILE_IS_DIRECTORY.getMessageCode(),
                sourceFile);
          }
        }
      }
    }
    else if (commandType == CommandType.DOWNLOAD)
    {
      for (String sourceFile : sourceFiles)
      {
        FileMetadata fileMetadata = new FileMetadata();
        fileMetadataMap.put(sourceFile, fileMetadata);
        fileMetadata.srcFileName = sourceFile;
        fileMetadata.destFileName = sourceFile.substring(
                sourceFile.lastIndexOf("/") + 1); // s3 uses / as separator
      }
    }
  }

  /**
   * Derive compression type from mime type
   *
   * @param mimeTypeStr
   * @return
   * @throws MimeTypeParseException
   */
  private FileCompressionType mimeTypeToCompressionType(String mimeTypeStr)
          throws MimeTypeParseException
  {
    MimeType mimeType = null;

    if (mimeTypeStr != null)
      mimeType = new MimeType(mimeTypeStr);

    if (mimeType != null && mimeType.getSubType() != null)
      return FileCompressionType.lookupByMimeSubType(
              mimeType.getSubType().toLowerCase());

    return null;
  }

  /**
   * Detect file compression type for all files to be uploaded
   *
   * @throws SnowflakeSQLException
   */
  private void processFileCompressionTypes() throws SnowflakeSQLException
  {
    // see what user has told us about the source file compression types
    boolean autoDetect = true;
    FileCompressionType userSpecifiedSourceCompression = null;

    if (SOURCE_COMPRESSION_AUTO_DETECT.equalsIgnoreCase(sourceCompression))
    {
      autoDetect = true;
    }
    else if (SOURCE_COMPRESSION_NONE.equalsIgnoreCase(sourceCompression))
    {
      autoDetect = false;
    }
    else
    {
      userSpecifiedSourceCompression =
      FileCompressionType.lookupByMimeSubType(sourceCompression.toLowerCase());

      if (userSpecifiedSourceCompression == null)
      {
        throw new SnowflakeSQLException(SqlState.FEATURE_NOT_SUPPORTED,
                ErrorCode.COMPRESSION_TYPE_NOT_KNOWN.getMessageCode(),
                sourceCompression);
      }
      else if (!userSpecifiedSourceCompression.isSupported())
      {
        throw new SnowflakeSQLException(SqlState.FEATURE_NOT_SUPPORTED,
            ErrorCode.COMPRESSION_TYPE_NOT_SUPPORTED.getMessageCode(),
            sourceCompression);
      }

      autoDetect = false;
    }

    if (!sourceFromStream)
    {
      for (String srcFile : sourceFiles)
      {
        FileMetadata fileMetadata = fileMetadataMap.get(srcFile);

        if (fileMetadata.resultStatus == ResultStatus.NONEXIST ||
            fileMetadata.resultStatus == ResultStatus.DIRECTORY)
          continue;

        File file = new File(srcFile);
        String srcFileName = file.getName();

        String mimeTypeStr = null;
        MimeType mimeType = null;

        FileCompressionType currentFileCompressionType = null;

        try
        {
          if (autoDetect)
          {
            // probe the file for compression type using tika file type detector
            mimeTypeStr = Files.probeContentType(file.toPath());

            if (mimeTypeStr == null)
            {
              try (FileInputStream f = new FileInputStream(file))
              {
                byte[] magic = new byte[4];
                if (f.read(magic, 0, 4) == 4 &&
                    Arrays.equals(magic, new byte[]{'P','A','R','1'}))
                {
                  mimeTypeStr = "snowflake/parquet";
                }
              }

            }

            if (mimeTypeStr != null)
            {
              logger.debug("Mime type for {} is: {}", srcFile, mimeTypeStr);

              currentFileCompressionType=mimeTypeToCompressionType(mimeTypeStr);
            }

            // fallback: use file extension
            if (currentFileCompressionType == null)
            {
              mimeTypeStr = getMimeTypeFromFileExtension(srcFile);

              if (mimeTypeStr != null)
              {
                logger.debug("Mime type for {} is: {}", srcFile, mimeTypeStr);
                currentFileCompressionType=mimeTypeToCompressionType(mimeTypeStr);
              }
            }
          }
          else
          {
            currentFileCompressionType = userSpecifiedSourceCompression;
          }

          // check if the compression type is supported by us
          if (currentFileCompressionType != null)
          {
            fileMetadata.srcCompressionType = currentFileCompressionType;

            if (currentFileCompressionType.isSupported())
            {
              // remember the compression type if supported
              fileMetadata.destCompressionType = currentFileCompressionType;
              fileMetadata.requireCompress = false;
              fileMetadata.destFileName = srcFileName;
              logger.debug("File compression detected as {} for: {}",
                        currentFileCompressionType.name(), srcFile);
            }
            else
            {
              // error if not supported
              throw new SnowflakeSQLException(SqlState.FEATURE_NOT_SUPPORTED,
                  ErrorCode.COMPRESSION_TYPE_NOT_SUPPORTED.getMessageCode(),
                  currentFileCompressionType.name());
            }
          }
          else
          {
            // we want to auto compress the files unless the user has disabled it
            logger.debug("Compression not found for file: {}", srcFile);

            // Set compress flag
            fileMetadata.requireCompress = autoCompress;
            fileMetadata.srcCompressionType = null;

            if (autoCompress)
            {
              // We only support gzip auto compression
              fileMetadata.destFileName = srcFileName +
                  FileCompressionType.GZIP.fileExtension;
              fileMetadata.destCompressionType = FileCompressionType.GZIP;
            }
            else
            {
              fileMetadata.destFileName = srcFileName;
              fileMetadata.destCompressionType = null;
            }
          }
        }
        catch (MimeTypeParseException ex)
        {
          logger.error(
              "Exception encountered when processing file compression types",
              ex);

          fileMetadata.resultStatus = ResultStatus.ERROR;
          fileMetadata.errorDetails = "Failed to parse mime type: " +
              mimeTypeStr;
        }
        catch (Exception ex)
        {

          // SNOW-13146: don't log severe message for user error
          if (ex instanceof SnowflakeSQLException)
            logger.debug(
              "Exception encountered when processing file compression types",
              ex);
          else
            logger.debug(
              "Exception encountered when processing file compression types",
              ex);

          fileMetadata.resultStatus = ResultStatus.ERROR;
          fileMetadata.errorDetails = ex.getMessage();
        }
      }
    }
    else
    {
      FileMetadata fileMetadata = fileMetadataMap.get(SRC_FILE_NAME_FOR_STREAM);
      fileMetadata.srcCompressionType = userSpecifiedSourceCompression;

      if (compressSourceFromStream)
        fileMetadata.destCompressionType = FileCompressionType.GZIP;
      else
        fileMetadata.destCompressionType = userSpecifiedSourceCompression;

      // add gz extension if file name doesn't have it
      if (compressSourceFromStream &&
          !destFileNameForStreamSource.endsWith(
              FileCompressionType.GZIP.fileExtension))
        fileMetadata.destFileName = destFileNameForStreamSource +
            FileCompressionType.GZIP.fileExtension;
      else
        fileMetadata.destFileName = destFileNameForStreamSource;
    }
  }

  /**
   * Derive mime type from file extension
   *
   * @param srcFile
   * @return
   */
  private String getMimeTypeFromFileExtension(String srcFile)
  {
    String srcFileLowCase = srcFile.toLowerCase();

    for (FileCompressionType compressionType : FileCompressionType.values())
    {
      if (srcFileLowCase.endsWith(compressionType.fileExtension))
      {
        return compressionType.mimeType + "/" +
            compressionType.mimeSubTypes.get(0);
      }
    }

    return null;
  }

  /**
   * A small helper for creating an S3 client.
   *
   * @return
   */
  private static SnowflakeS3Client createS3Client(Map stageCredentials,
                                                  int parallel,
                                                  String stageRegion)
          throws SnowflakeSQLException
  {
    return createS3Client(stageCredentials, parallel, null, stageRegion);
  }

  /**
   * A small helper for creating an S3 client.
   *
   * @return
   */
  private static SnowflakeS3Client createS3Client(Map stageCredentials,
                                               int parallel,
                                               S3FileEncryptionMaterial encMat,
                                               String stageRegion)
          throws SnowflakeSQLException
  {
    logger.debug("createS3Client encryption={}", (encMat == null ? "no" : "yes"));


    SnowflakeS3Client s3Client;

    String awsID = (String)stageCredentials.get("AWS_ID");
    String awsKey = (String)stageCredentials.get("AWS_KEY");
    String awsToken = (String)stageCredentials.get("AWS_TOKEN");

    logger.debug("aws id={}, aws key={}, aws token={}",
                awsID, awsKey, awsToken);

    // initialize aws credentials
    AWSCredentials awsCredentials = (awsToken != null) ?
        new BasicSessionCredentials(awsID, awsKey, awsToken)
      : new BasicAWSCredentials(awsID, awsKey);

    ClientConfiguration clientConfig = new ClientConfiguration();
    clientConfig.setMaxConnections(parallel+1);
    clientConfig.setMaxErrorRetry(S3_TRANSFER_MAX_RETRIES);

    logger.debug("s3 client configuration: maxConnection={}, connectionTimeout={}, " +
                  "socketTimeout={}, maxErrorRetry={}",
                  clientConfig.getMaxConnections(),
                  clientConfig.getConnectionTimeout(),
                  clientConfig.getSocketTimeout(),
                  clientConfig.getMaxErrorRetry());

    String httpsProxyHost = System.getProperty("https.proxyHost");
    // set up http proxy from system properties
    if (httpsProxyHost != null)
    {
      clientConfig.setProxyHost(httpsProxyHost);

      clientConfig.setProxyPort(Integer.parseInt(
              System.getProperty("https.proxyPort")));
    }

    try
    {
      s3Client = new SnowflakeS3Client(awsCredentials, clientConfig, encMat, stageRegion);
    }
    catch(Throwable ex)
    {
      logger.debug("Exception creating s3 client", ex);
      throw ex;
    }
    logger.debug("s3 client created");

    return s3Client;
  }

  /**
   * A small helper for extracting bucket name and path from stage location.
   *
   * @param stageLocation stage location
   * @return s3 location
   */
  static public S3Location extractBucketNameAndPath(String stageLocation)
  {
    String bucketName = stageLocation;
    String s3path = "";

    // split stage location as bucket name and path
    if (stageLocation.contains("/"))
    {
      bucketName = stageLocation.substring(0, stageLocation.indexOf("/"));
      s3path = stageLocation.substring(stageLocation.indexOf("/")+1);
    }

    return new S3Location(bucketName, s3path);
  }

  /**
   * Describe the metadata of a fixed view.
   *
   * @return list of column meta data
   * @throws Exception failed to construct list
   */
  @Override
  public List<SnowflakeColumnMetadata> describeColumns() throws Exception
  {
    return SnowflakeUtil.describeFixedViewColumns(
        commandType == CommandType.UPLOAD?
        (showEncryptionParameter ?
          UploadCommandEncryptionFacade.class : UploadCommandFacade.class) :
        (showEncryptionParameter ?
          DownloadCommandEncryptionFacade.class : DownloadCommandFacade.class));
  }

  @Override
  public List<Object> getNextRow() throws Exception
  {
    if(currentRowIndex < statusRows.size())
    {
      return ClassUtil.getFixedViewObjectAsRow(
          commandType == CommandType.UPLOAD?
          (showEncryptionParameter ?
            UploadCommandEncryptionFacade.class : UploadCommandFacade.class) :
          (showEncryptionParameter ?
            DownloadCommandEncryptionFacade.class : DownloadCommandFacade.class),
          statusRows.get(currentRowIndex++));
    }
    else
    {
      return null;
    }
  }

  /**
   * Generate status rows for each file
   */
  private void createStatusRows()
  {
    statusRows = new ArrayList<Object>();

    for (Map.Entry<String, FileMetadata> entry : fileMetadataMap.entrySet())
    {
      FileMetadata fileMetadata = entry.getValue();

      if (commandType == CommandType.UPLOAD)
      {
        statusRows.add(showEncryptionParameter ?
            new UploadCommandEncryptionFacade(
                fileMetadata.srcFileName,
                fileMetadata.destFileName,
                fileMetadata.resultStatus.name(),
                fileMetadata.errorDetails,
                fileMetadata.srcFileSize,
                fileMetadata.destFileSize,
                (fileMetadata.srcCompressionType==null)?
                "NONE":fileMetadata.srcCompressionType.name(),
                (fileMetadata.destCompressionType==null)?
                "NONE":fileMetadata.destCompressionType.name(),
                fileMetadata.isEncrypted) :
            new UploadCommandFacade(
                fileMetadata.srcFileName,
                fileMetadata.destFileName,
                fileMetadata.resultStatus.name(),
                fileMetadata.errorDetails,
                fileMetadata.srcFileSize,
                fileMetadata.destFileSize,
                (fileMetadata.srcCompressionType==null)?
                "NONE":fileMetadata.srcCompressionType.name(),
                (fileMetadata.destCompressionType==null)?
                "NONE":fileMetadata.destCompressionType.name()));
      }
      else if (commandType == CommandType.DOWNLOAD)
      {
        statusRows.add(showEncryptionParameter ?
                new DownloadCommandEncryptionFacade(
                        fileMetadata.srcFileName.startsWith("/")?
                        fileMetadata.srcFileName.substring(1):
                        fileMetadata.srcFileName,
                        fileMetadata.resultStatus.name(),
                        fileMetadata.errorDetails,
                        fileMetadata.destFileSize,
                        fileMetadata.isEncrypted) :
                new DownloadCommandFacade(
                        fileMetadata.srcFileName.startsWith("/")?
                        fileMetadata.srcFileName.substring(1):
                        fileMetadata.srcFileName,
                        fileMetadata.resultStatus.name(),
                        fileMetadata.errorDetails,
                        fileMetadata.destFileSize));
      }

    }

    /* we sort the result if the connection is in sorting mode
     */
    Object sortProperty = null;

    sortProperty =
        connection.getSFSessionProperty("sort");

    boolean sortResult = sortProperty != null && (Boolean)sortProperty;

    if (sortResult)
    {
      Comparator comparator =
      (commandType == CommandType.UPLOAD)?
      new Comparator<Object>()
      {
        public int compare(Object a, Object b)
        {
          String srcFileNameA = ((UploadCommandFacade)a).srcFile;
          String srcFileNameB = ((UploadCommandFacade)b).srcFile;

          return srcFileNameA.compareTo(srcFileNameB);
        }
      }:
      new Comparator<Object>()
      {
        public int compare(Object a, Object b)
        {
          String srcFileNameA = ((DownloadCommandFacade)a).file;
          String srcFileNameB = ((DownloadCommandFacade)b).file;

          return srcFileNameA.compareTo(srcFileNameB);
        }
      };

      // sort the rows by source file names
      Collections.sort(statusRows, comparator);
    }
  }

  public Object getResultSet()
          throws SnowflakeSQLException
  {
    return new SFFixedViewResultSet(this);
  }

  public CommandType getCommandType()
  {
    return commandType;
  }

  public void setSourceStream(InputStream sourceStream)
  {
    this.sourceStream = sourceStream;
    this.sourceFromStream = true;
  }

  public void setDestFileNameForStreamSource(
      String destFileNameForStreamSource)
  {
    this.destFileNameForStreamSource = destFileNameForStreamSource;
  }

  public void setSourceStreamSize(long sourceStreamSize)
  {
    this.sourceStreamSize = sourceStreamSize;
  }

  public void setCompressSourceFromStream(boolean compressSourceFromStream)
  {
    this.compressSourceFromStream = compressSourceFromStream;
  }

  public void setOverwrite(boolean overwrite)
  {
    this.overwrite = overwrite;
  }
}