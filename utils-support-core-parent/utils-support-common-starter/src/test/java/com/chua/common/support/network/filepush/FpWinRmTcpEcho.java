package com.chua.common.support.network.filepush;
import com.chua.winrm.support.client.WinRmExecClient;
public class FpWinRmTcpEcho {
    public static void main(String[] args) throws Exception {
        WinRmExecClient wr = WinRmExecClient.builder()
                .host("172.16.9.194").port(5985)
                .username("lenovo").password("123")
                .authenticationScheme("NTLM").build();
        wr.connect();
        // 用远程 JRE 起一个 echo server 在 9999 端口（用 jshell 不行，直接用 cmd 后台跑）
        // 更简单：写个 echo 脚本到远程并启动
        wr.exec().command("cmd.exe /c powershell -Command \"Set-Content D:\\tcp-echo.ps1 @'"'@'
$listener = [System.Net.Sockets.TcpListener]':::