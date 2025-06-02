package pt.ulisboa.tecnico.cnv.common;

public class InstanceMetadata {
    public static final String INSTANCE_ID;

    static {
        String instanceId;

        if ("true".equalsIgnoreCase(System.getenv("LOCAL_MODE"))) {
            instanceId = "local-instance";
        } else {
            try {
                instanceId = com.amazonaws.util.EC2MetadataUtils.getInstanceId();
            } catch (com.amazonaws.AmazonClientException e) {
                instanceId = "local-instance";
            }
        }

        INSTANCE_ID = instanceId;
    }
}
