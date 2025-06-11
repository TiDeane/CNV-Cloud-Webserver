package pt.ulisboa.tecnico.cnv.loadbalancer;

public final class Constants {
    //Number of retries for Lambda Requests
    public static final Integer RETRIES = 5;

    //Port for worker handlers
    public static final String WORKER_PORT = "8000";

    //AWS region to deploy the infrastructure
    public static final String AWS_REGION = "eu-west-1";

    //Parameters for linear regression to estimate time cost of requests
    public static final double ALPHA = -1.4726764102867243e-6;
    public static final double BETA = 6.223594986449469e-5;
    public static final double INTERCEPT = 206.2278774369248;
    public static final double THRESHOLD = 4100.0;

    //Game Of Life LBHandler specific constants
    public static final String GOL_JAR_PATH = "/home/tomas/CNVf2/cnv25-g28/gameoflife/target/gameoflife-1.0.0-SNAPSHOT-jar-with-dependencies.jar";
    public static final String GOL_HANDLER = "GameOfLifeHandler";
    public static final String GOL_PATH = "gameoflife";

    //Fifteen Puzzle LBHandler specific constants
    public static final String FP_JAR_PATH = "/home/tomas/CNVf2/cnv25-g28/fifteenpuzzle/target/fifteenpuzzle-1.0.0-SNAPSHOT-jar-with-dependencies.jar";    
    public static final String FP_HANDLER = "FifteenPuzzleHandler";
    public static final String FP_PATH = "fifteenpuzzle";

    //Capture The Flag LBHandler specific constants
    public static final String CTF_JAR_PATH = "/home/tomas/CNVf2/cnv25-g28/capturetheflag/target/capturetheflag-1.0.0-SNAPSHOT-jar-with-dependencies.jar";
    public static final String CTF_HANDLER = "CaptureTheFlagHandler";
    public static final String CTF_PATH = "capturetheflag";
    
    
    
    
}
