package cn.keking.utils;

import cn.keking.config.ConfigConstants;
import cn.keking.hutool.URLUtil;
import cn.keking.model.FileAttribute;
import cn.keking.model.FileType;
import cn.keking.model.ReturnResponse;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * @author yudian-it
 */
@Component
public class DownloadUtils {

    private final Logger logger = LoggerFactory.getLogger(DownloadUtils.class);

    private final String fileDir = ConfigConstants.getFileDir();

    private final FileUtils fileUtils;

    public DownloadUtils(FileUtils fileUtils) {
        this.fileUtils = fileUtils;
    }

    private static final String URL_PARAM_FTP_USERNAME = "ftp.username";
    private static final String URL_PARAM_FTP_PASSWORD = "ftp.password";
    private static final String URL_PARAM_FTP_CONTROL_ENCODING = "ftp.control.encoding";

    /**
     * @param fileAttribute fileAttribute
     * @param fileName 文件名
     * @return 本地文件绝对路径
     */
    public ReturnResponse<String> downLoad(FileAttribute fileAttribute, String  fileName) {
        String urlStr = fileAttribute.getUrl();
        String type = fileAttribute.getSuffix();
        ReturnResponse<String> response = new ReturnResponse<>(0, "下载成功!!!", "");
        UUID uuid = UUID.randomUUID();
        if (null == fileName) {
            fileName = uuid+ "."+type;
        } else { // 文件后缀不一致时，以type为准(针对simText【将类txt文件转为txt】)
            fileName = fileName.replace(fileName.substring(fileName.lastIndexOf(".") + 1), type);
        }
        String realPath = fileDir + fileName;
        File dirFile = new File(fileDir);
        if (!dirFile.exists()) {
            dirFile.mkdirs();
        }
        try {
            //表明不是通过htpp来进行的,这里就进行的扩展
            //这里初步是支持 minio 获取
            if(!urlStr.contains("http")){
                //获取minio
                byte[] bytes = getBytesFromMinioUrl(urlStr);
                OutputStream os = new FileOutputStream(new File(realPath));
                saveBytesToOutStream(bytes, os);
            }else{

                //这里是以前的方式
                URL url = new URL(urlStr);
                if (url.getProtocol() != null && url.getProtocol().toLowerCase().startsWith("http")) {
                    byte[] bytes = getBytesFromUrl(urlStr);
                    OutputStream os = new FileOutputStream(new File(realPath));
                    saveBytesToOutStream(bytes, os);
                } else if (url.getProtocol() != null && "ftp".equals(url.getProtocol().toLowerCase())) {
                    String ftpUsername = fileUtils.getUrlParameterReg(fileAttribute.getUrl(), URL_PARAM_FTP_USERNAME);
                    String ftpPassword = fileUtils.getUrlParameterReg(fileAttribute.getUrl(), URL_PARAM_FTP_PASSWORD);
                    String ftpControlEncoding = fileUtils.getUrlParameterReg(fileAttribute.getUrl(), URL_PARAM_FTP_CONTROL_ENCODING);
                    FtpUtils.download(fileAttribute.getUrl(), realPath, ftpUsername, ftpPassword, ftpControlEncoding);
                } else {
                    response.setCode(1);
                    response.setContent(null);
                    response.setMsg("url不能识别url" + urlStr);
                }
            }


            response.setContent(realPath);
            response.setMsg(fileName);
            if(FileType.simText.equals(fileAttribute.getType())){
                convertTextPlainFileCharsetToUtf8(realPath);
            }
            return response;

        } catch (IOException e) {
            logger.error("文件下载失败，url：{}", urlStr, e);
            response.setCode(1);
            response.setContent(null);
            if (e instanceof FileNotFoundException) {
                response.setMsg("文件不存在!!!");
            } else {
                response.setMsg(e.getMessage());
            }
            return response;
        }
    }

    public byte[] getBytesFromUrl(String urlStr) throws IOException {
        InputStream is = getInputStreamFromUrl(urlStr);
        if (is != null) {
            return getBytesFromStream(is);
        } else {
            urlStr = URLUtil.normalize(urlStr, true, true);
            is = getInputStreamFromUrl(urlStr);
            if (is == null) {
                logger.error("文件下载异常：url：{}", urlStr);
                throw new IOException("文件下载异常：url：" + urlStr);
            }
            return getBytesFromStream(is);
        }
    }

    public byte[] getBytesFromMinioUrl(String urlStr) throws IOException {
        InputStream is = getInputStreamFromUrl2(urlStr);
        if (is != null) {
            return getBytesFromStream(is);
        } else {
            urlStr = URLUtil.normalize(urlStr, true, true);
            is = getInputStreamFromUrl2(urlStr);
            if (is == null) {
                logger.error("minio文件下载异常：url：{}", urlStr);
                throw new IOException("minio文件下载异常：url：" + urlStr);
            }
            return getBytesFromStream(is);
        }
    }

    public void saveBytesToOutStream(byte[] b, OutputStream os) throws IOException {
        os.write(b);
        os.close();
    }

    private InputStream getInputStreamFromUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            URLConnection connection = url.openConnection();
            if (connection instanceof HttpURLConnection) {
                connection.setRequestProperty("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT; DigExt)");
            }
            return connection.getInputStream();
        } catch (IOException e) {
            logger.warn("连接url异常：url：{}", urlStr);
            return null;
        }
    }

    //通过minio获取
    private InputStream getInputStreamFromUrl2(String urlStr) {
        try {
            //目前这里定死了，后面可以用配置文件来读取
            MinioClient  minioClient = new MinioClient("http://192.168.56.1:9000/", "zhengyu123", "zhengyu123");
            //目前附件的信息全部放到 attach里面，否则有
            return minioClient.getObject("attach",urlStr);
        } catch (Exception e) {
            logger.warn("连接url异常：url：{}", urlStr);
            return null;
        }
    }

    private byte[] getBytesFromStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len = 0;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        byte[] b = baos.toByteArray();
        is.close();
        baos.close();
        return b;
    }

  /**
   * 转换文本文件编码为utf8
   * 探测源文件编码,探测到编码切不为utf8则进行转码
   * @param filePath 文件路径
   */
  private static void convertTextPlainFileCharsetToUtf8(String filePath) throws IOException {
    File sourceFile = new File(filePath);
    if(sourceFile.exists() && sourceFile.isFile() && sourceFile.canRead()) {
      String encoding = null;
      try {
        FileCharsetDetector.Observer observer = FileCharsetDetector.guessFileEncoding(sourceFile);
        // 为准确探测到编码,不适用猜测的编码
        encoding = observer.isFound()?observer.getEncoding():null;
        // 为准确探测到编码,可以考虑使用GBK  大部分文件都是windows系统产生的
      } catch (IOException e) {
        // 编码探测失败,
        e.printStackTrace();
      }
      if(encoding != null && !"UTF-8".equals(encoding)){
        // 不为utf8,进行转码
        File tmpUtf8File = new File(filePath+".utf8");
        Writer writer = new OutputStreamWriter(new FileOutputStream(tmpUtf8File), StandardCharsets.UTF_8);
        Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(sourceFile),encoding));
        char[] buf = new char[1024];
        int read;
        while ((read = reader.read(buf)) > 0){
          writer.write(buf, 0, read);
        }
        reader.close();
        writer.close();
        // 删除源文件
        sourceFile.delete();
        // 重命名
        tmpUtf8File.renameTo(sourceFile);
      }
    }
  }
}
