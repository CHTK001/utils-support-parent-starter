package com.chua.common.support.network.filepush;
import com.chua.winrm.support.client.WinRmExecClient;
/**
 * FpWinRmTcpEcho类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
public class FpWinRmTcpEcho {
    /**
     * 程序入口，运行示例自检。
     *
     * @param args 参数，不允许为 null
     * @throws Exception 当执行过程不满足前置条件时
     */
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