package pt.ulisboa.tecnico.cnv.common;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class Constants {
    //Number of retries for Lambda Requests
    public static final Integer RETRIES = 3;

    //Port for worker handlers
    public static final String WORKER_PORT = "8000";

    //AWS region to deploy the infrastructure (AWS_DEFAULT_REGION from config.sh, eu-west-1 if unset)
    public static final String AWS_REGION = System.getenv().getOrDefault("AWS_DEFAULT_REGION", "eu-west-1");

    //Game Of Life LBHandler specific constants
    public static final String GOL_HANDLER = "GameOfLifeHandler";
    public static final String GOL_PATH = "gameoflife";

    //Fifteen Puzzle LBHandler specific constants
    public static final String FP_HANDLER = "FifteenPuzzleHandler";
    public static final String FP_PATH = "fifteenpuzzle";

    //Capture The Flag LBHandler specific constants
    public static final String CTF_HANDLER = "CaptureTheFlagHandler";
    public static final String CTF_PATH = "capturetheflag";
    
    public static final ConcurrentMap<String, String> activeRequests = new ConcurrentHashMap<>();

    //Parameters for linear regressions for each handler to estimate time cost of requests
    public static final double FP_ALPHA = -1.939440e-05;
    public static final double FP_BETA = 3.616559e-04;
    public static final double FP_INTERCEPT = 323.143241;
    public static final double FP_RMSE = 28.0012;
    public static final double FP_MAE = 20.0912;
    public static final double FP_THRESHOLD = 4000.0;

    public static final double GOL_ALPHA = 7.561869e-05;
    public static final double GOL_BETA = 2.889375e-07;
    public static final double GOL_INTERCEPT = 592.717455;
    public static final double GOL_RMSE = 74.6370;
    public static final double GOL_MAE = 61.3223;
    public static final double GOL_THRESHOLD = 4000.0;

    public static final double CTF_ALPHA = 7.465307e-04;
    public static final double CTF_BETA = -9.569647e-03;
    public static final double CTF_INTERCEPT = 2226.599713;
    public static final double CTF_RMSE = 5075.9989;
    public static final double CTF_MAE = 3783.4742;
    public static final double CTF_THRESHOLD = 6000.0;
}
