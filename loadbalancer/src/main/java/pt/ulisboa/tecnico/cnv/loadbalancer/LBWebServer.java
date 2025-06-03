package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;

public class LBWebServer {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8001), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new LBRootHandler());
        server.createContext("/gameoflife", new LBGameOfLifeHandler());
        server.createContext("/fifteenpuzzle", new LBFifteenPuzzleHandler());
        server.createContext("/capturetheflag", new LBCaptureTheFlagHandler());
        server.start();
    }
}
