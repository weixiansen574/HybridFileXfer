package top.weixiansen574.hybridfilexfer;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;

public class ADBTest {
    public static void main(String[] args)  {
        try {
            File file = new File("/storage/emulated/0/Android/data/com.tencent.mm/files/external_used_mark");
            FileInputStream fileInputStream = new FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            DataInputStream dataInputStream = new DataInputStream(fileInputStream);
            dataInputStream.readFully(buffer);
            System.out.println(new String(buffer));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
