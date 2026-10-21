Option Explicit
Dim shell, fs, folder, jar, java, javaHome
Set shell = CreateObject("WScript.Shell")
Set fs = CreateObject("Scripting.FileSystemObject")
folder = fs.GetParentFolderName(WScript.ScriptFullName)
jar = folder & "\HybridFileXfer.jar"
If Not fs.FileExists(jar) Then
    MsgBox "Extract this launcher beside HybridFileXfer.jar, or use the packaged HybridFileXfer.exe.", 48, "HybridFileXfer"
    WScript.Quit 1
End If
java = folder & "\runtime\bin\javaw.exe"
If Not fs.FileExists(java) Then
    javaHome = shell.ExpandEnvironmentStrings("%JAVA_HOME%")
    java = javaHome & "\bin\javaw.exe"
    If Not fs.FileExists(java) Then java = "javaw.exe"
End If
shell.CurrentDirectory = folder
On Error Resume Next
shell.Run Chr(34) & java & Chr(34) & " -Dfile.encoding=UTF-8 -jar " & Chr(34) & jar & Chr(34) & " --gui", 0, False
If Err.Number <> 0 Then MsgBox "Java could not start. Use the packaged desktop application or install a Java runtime.", 48, "HybridFileXfer"
