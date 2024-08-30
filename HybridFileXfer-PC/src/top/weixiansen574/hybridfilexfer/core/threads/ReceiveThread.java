package top.weixiansen574.hybridfilexfer.core.threads;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.concurrent.BlockingDeque;

import top.weixiansen574.hybridfilexfer.core.TransferIdentifiers;
import top.weixiansen574.hybridfilexfer.core.bean.FileTransferEvent;

public class ReceiveThread extends TransferThread {
    private final DataInputStream dis;

    public ReceiveThread(BlockingDeque<FileTransferEvent> events, int device, InputStream dis) {
        super(events, device);
        this.dis = new DataInputStream(dis);
    }

    @Override
    public void run() {
        try {
            short identifier;
            while ((identifier = dis.readShort()) != TransferIdentifiers.END_POINT) {
                if (identifier == TransferIdentifiers.FILE){
                    String filePath = dis.readUTF();//文件路径
                    filePath = replaceWindowsInvalidChars(filePath);

                    long lastModified = dis.readLong();//修改时间
                    long remainingLength = dis.readLong();//文件大小
                    String desc = String.format(Locale.getDefault(),"[%.2fMB] %s",
                            ((float) remainingLength) / 1024 / 1024,
                            filePath);
                    addEvent(FileTransferEvent.STATE_DOWNLOAD,desc); // 双端阻塞队列 好像跟线程并发有关，没完全明白

                    boolean existSame = checkFileSize(filePath, remainingLength);


                    File file = new File(filePath);
                    File parentFile = file.getParentFile();
                    if (parentFile != null && !file.getParentFile().exists()) {
                        parentFile.mkdirs();
                    }

                    FileOutputStream fileOutputStream = null; // 可能尚未初始化变量fileOutputStream
                    if (existSame == false) fileOutputStream = new FileOutputStream(file); // 这里不能运行，否则会把文件置空
                    byte[] buffer = new byte[4096];
                    while (remainingLength > 0) {
                        int read;
                        if (remainingLength >= buffer.length) {
                            read = dis.read(buffer); // 懂了 不能直接跳过这里的read，不然identifier = dis.readShort()会不对
                        } else {
                            int len = (int) remainingLength;
                            read = dis.read(buffer, 0, len);
                        }
                        remainingLength -= read;
                        if (existSame == false) fileOutputStream.write(buffer, 0, read);
                        transferredBytes += read;
                    }
                    if (existSame == false) fileOutputStream.close();
                    if (existSame == false) {
                        file.setLastModified(lastModified);//文件传输完成后将修改日期设置成与手机内一致的
                    } else{
                        System.out.print("跳过该文件：");
                    }




                    System.out.println("{"+Thread.currentThread().getName()+"}"+desc);
                } else if (identifier == TransferIdentifiers.FOLDER){
                    String filePath = dis.readUTF();//文件路径
                    long lastModified = dis.readLong();//修改时间
                    File file = new File(filePath);
                    if (!file.exists()){
                        file.mkdirs(); // 这里好像没用 并不会创建Windows的文件夹？
                    }
                    file.setLastModified(lastModified);
                    System.out.println(filePath);
                } else if (identifier == TransferIdentifiers.FILE_SLICE){
                    String filePath = dis.readUTF();//文件路径
                    long lastModified = dis.readLong();//修改时间
                    long totalSize = dis.readLong();//总大小
                    long startRange = dis.readLong();//起始点
                    long endRange = dis.readLong();//结束点
                    String desc = String.format(Locale.getDefault(),"[%dMB-%dMB/%dMB] %s",
                            startRange / 1024 / 1024,
                            endRange / 1024 / 1024,
                            totalSize/ 1024 / 1024,
                            filePath);
                    addEvent(FileTransferEvent.STATE_DOWNLOAD,desc);


                    boolean existSame = checkFileSize(filePath, totalSize);

                    File file = new File(filePath);
                    File parentFile = file.getParentFile();
                    if (parentFile != null && !file.getParentFile().exists()){
                        parentFile.mkdirs();
                    }

                    RandomAccessFile randomAccessFile = null;
                    if (existSame == false) {
                        randomAccessFile = newRAF(file, totalSize);
                        randomAccessFile.seek(startRange);
                    } else{
                        System.out.print("跳过该文件片段：");
                    }

                    int downloadLength = (int) (endRange - startRange);
                    System.out.println("{"+Thread.currentThread().getName()+"}"+desc);

                    byte[] buffer = new byte[4096];
                    boolean canContinue = true;
                    while (canContinue) {
                        int len;
                        if (downloadLength >= 4096) {
                            len = 4096;
                            downloadLength -= 4096;
                        } else {
                            len = downloadLength;
                            canContinue = false;
                        }
                        dis.readFully(buffer, 0, len);
                        if (existSame == false) randomAccessFile.write(buffer, 0, len);
                        transferredBytes += len;
                    }
                    if (existSame == false) randomAccessFile.close();
                    file.setLastModified(lastModified);
                    addEvent(FileTransferEvent.STATE_OVER,desc);
                }
            }
            System.out.println(getName() + " 接收线程已终止，因为收到了关闭指令");
        } catch (IOException e) {
            System.out.println(getName() + " 接收线程已终止，因为发生了异常");
            e.printStackTrace();
            if (onExceptionListener != null){
                onExceptionListener.onException(e);
            }
        }
    }

    public static synchronized RandomAccessFile newRAF(File path,long totalSize) throws IOException {
        if (!path.exists()) {
            RandomAccessFile randomAccessFile = new RandomAccessFile(path,"rw");
            randomAccessFile.setLength(totalSize);
            return randomAccessFile;
        } else {
            return new RandomAccessFile(path,"rw");
        }
    }

    public void close(){
        try {
            dis.close();
        } catch (IOException ignored) {
        }
    }

    // 不能只替换最后一个路径名
    public static String replaceWindowsInvalidChars(String filePath) {
        // 找到第一个斜杠的位置
        int firstSlashIndex = filePath.indexOf('/');
        if (firstSlashIndex == -1) {
            // 如果没有找到斜杠，返回原始文件路径
            return filePath;
        }

        // 提取第一个斜杠之前和之后的部分
        String beforeFirstSlash = filePath.substring(0, firstSlashIndex + 1);
        String afterFirstSlash = filePath.substring(firstSlashIndex + 1);

        // 使用正则表达式删除问号和冒号
        afterFirstSlash = afterFirstSlash.replaceAll("[:*?\"<>|]", "...");// 替换在Windows中不被允许的字符

        // 重新组合路径
        return beforeFirstSlash + afterFirstSlash;
    }

    public static boolean checkFileSize(String filePath, long expectedSize) {
        File file = new File(filePath);

        // 检查文件是否存在
        if (file.exists() && file.isFile()) {
            // 检查文件大小是否相同
            return file.length() == expectedSize;
        }

        // 文件不存在或不是一个文件
        return false;
    }
}
