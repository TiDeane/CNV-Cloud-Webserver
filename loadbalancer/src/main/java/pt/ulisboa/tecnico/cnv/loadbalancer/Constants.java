package pt.ulisboa.tecnico.cnv.loadbalancer;

public final class Constants {
    //Number of retries for Lambda Requests
    public static final Integer RETRIES = 3;

    //Port for worker handlers
    public static final String WORKER_PORT = "8000";

    //AWS region to deploy the infrastructure
    public static final String AWS_REGION = "eu-west-1";

    //Parameters for linear regression to estimate time cost of requests
    public static final double ALPHA = 8.381637e-07;
    public static final double BETA = 6.541957e-06;
    public static final double INTERCEPT = 339.600299;
    public static final double THRESHOLD = 4100.0;

    //Game Of Life LBHandler specific constants
    public static final String GOL_HANDLER = "GameOfLifeHandler";
    public static final String GOL_PATH = "gameoflife";

    //Fifteen Puzzle LBHandler specific constants
    public static final String FP_HANDLER = "FifteenPuzzleHandler";
    public static final String FP_PATH = "fifteenpuzzle";

    //Capture The Flag LBHandler specific constants
    public static final String CTF_HANDLER = "CaptureTheFlagHandler";
    public static final String CTF_PATH = "capturetheflag";
        
}
