package top.weixiansen574.hybirdfilexfer.core;

import java.io.File;

public class Utils {
    public static String legalizationPath(String path){
        /*
        Linux支持反斜杠文件名，若不转换会生成带斜杠的文件名而不是一系列文件夹，
        但windows里的/也能表示一个分隔符，故统一换成/。
        Linux也少见带“\”的文件名，若出现只能被切成文件夹
        */
        if (File.separator.equals("/")){
            //如果当前是Linux端，向windows传输，windows不支持冒号文件名，换成-
            return path.replace(":","-");
            //如果不是，那就是windows，windows的":"作为盘符，强制替换会找不到盘符，导致默认存到运行路径，故不替换
        }
        return path;
    }

    public static String  replaceBackslashToSlash(String path){
        return path.replace("\\","/");
    }

    //Linux的尿性："\"可做为文件名！！！
    public static String replaceBackslashToUnderline(String path){
        return path.replace("\\","_");
    }

    public static String replaceColon(String path){
        return path.replace(":","-");
    }
}
