#!/bin/bash

source config.sh

# Create load balancer and configure health check.
aws elb create-load-balancer \
	--load-balancer-name CNV-LoadBalancer \
	--listeners "Protocol=HTTP,LoadBalancerPort=80,InstanceProtocol=HTTP,InstancePort=8000" \
  	--availability-zones us-east-1a

aws elb configure-health-check \
  	--load-balancer-name CNV-LoadBalancer \
  	--health-check Target=HTTP:8000/test,Interval=30,UnhealthyThreshold=2,HealthyThreshold=10,Timeout=5

# Create Launch Template
aws ec2 create-launch-template \
	--launch-template-name CNV-LaunchTemplate \
	--version-description "v1" \
	--launch-template-data "{
		\"ImageId\": \"$(cat image.id)\",
		\"InstanceType\": \"t2.micro\",
		\"KeyName\": \"$AWS_KEYPAIR_NAME\",
		\"SecurityGroupIds\": [\"$AWS_SECURITY_GROUP\"],
		\"Monitoring\": {\"Enabled\": true}
}"

# Create auto scaling group with launch template.
aws autoscaling create-auto-scaling-group \
  	--auto-scaling-group-name CNV-AutoScalingGroup \
  	--launch-template LaunchTemplateName=CNV-LaunchTemplate,Version=1 \
  	--load-balancer-names CNV-LoadBalancer \
  	--availability-zones us-east-1a \
  	--health-check-type ELB \
  	--health-check-grace-period 60 \
  	--min-size 1 \
  	--max-size 2 \
  	--desired-capacity 1

# Create scale out policy
arn_out=$(aws autoscaling put-scaling-policy \
  --auto-scaling-group-name CNV-AutoScalingGroup \
  --policy-name step-scale-out \
  --policy-type StepScaling \
  --adjustment-type ChangeInCapacity \
  --step-adjustments '[{"MetricIntervalLowerBound":0,"ScalingAdjustment":1}]' \
  --cooldown 60 \
  --metric-aggregation-type Average \
	--query 'PolicyARN' --output text)

# Create scale in policy
arn_in=$(aws autoscaling put-scaling-policy \
  --auto-scaling-group-name CNV-AutoScalingGroup \
  --policy-name step-scale-in \
  --policy-type StepScaling \
  --adjustment-type ChangeInCapacity \
  --step-adjustments '[{"MetricIntervalUpperBound":0,"ScalingAdjustment":-1}]' \
  --cooldown 60 \
  --metric-aggregation-type Average \
  --query 'PolicyARN' --output text)

# Create CloudWatch alarm for scaling out (CPU > 50% for 1 minute)
aws cloudwatch put-metric-alarm \
  --alarm-name CNV-HighCPUUtilizationAlarm \
  --metric-name CPUUtilization \
  --namespace AWS/EC2 \
  --statistic Average \
  --period 60 \
  --threshold 50 \
  --comparison-operator GreaterThanThreshold \
  --dimensions Name=AutoScalingGroupName,Value=CNV-AutoScalingGroup \
  --evaluation-periods 1 \
  --alarm-actions "$arn_out" \
  --unit Percent

# Create CloudWatch alarm for scaling in (CPU < 25% for 1 minute)
aws cloudwatch put-metric-alarm \
  --alarm-name CNV-LowCPUUtilizationAlarm \
  --metric-name CPUUtilization \
  --namespace AWS/EC2 \
  --statistic Average \
  --period 60 \
  --threshold 25 \
  --comparison-operator LessThanThreshold \
  --dimensions Name=AutoScalingGroupName,Value=CNV-AutoScalingGroup \
  --evaluation-periods 1 \
  --alarm-actions "$arn_in" \
  --unit Percent