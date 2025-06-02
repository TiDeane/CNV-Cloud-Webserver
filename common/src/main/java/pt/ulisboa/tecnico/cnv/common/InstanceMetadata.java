package pt.ulisboa.tecnico.cnv.common;

public class InstanceMetadata {
    public static final String INSTANCE_ID;

    static {
        String instanceId = "local-instance";

        if (!"true".equalsIgnoreCase(System.getenv("LOCAL_MODE"))) {
            try {
                String ec2Id = com.amazonaws.util.EC2MetadataUtils.getInstanceId();
                if (ec2Id != null && !ec2Id.isEmpty()) instanceId = ec2Id;
            } catch (com.amazonaws.AmazonClientException e) {
                // fall back to default instanceId
            }
        }

        INSTANCE_ID = instanceId;
    }
}
