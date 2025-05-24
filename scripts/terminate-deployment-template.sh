#!/bin/bash

source config.sh

# Delete scaling policies
aws autoscaling delete-policy \
    --auto-scaling-group-name CNV-AutoScalingGroup \
    --policy-name step-scale-out

aws autoscaling delete-policy \
    --auto-scaling-group-name CNV-AutoScalingGroup \
    --policy-name step-scale-in

# Delete CloudWatch alarms
aws cloudwatch delete-alarms \
    --alarm-names CNV-HighCPUUtilizationAlarm CNV-LowCPUUtilizationAlarm

# Update and delete Auto Scaling group.
aws autoscaling update-auto-scaling-group \
	--auto-scaling-group-name CNV-AutoScalingGroup \
  	--min-size 0 \
  	--max-size 0 \
  	--desired-capacity 0

# Optional: wait for instance termination before deletion
sleep 60

aws autoscaling delete-auto-scaling-group \
  	--auto-scaling-group-name CNV-AutoScalingGroup \
  	--force-delete

# Delete Launch Template.
aws ec2 delete-launch-template \
  	--launch-template-name CNV-LaunchTemplate

# Delete Load Balancer.
aws elb delete-load-balancer \
  	--load-balancer-name CNV-LoadBalancer

# Optional: delete AMI to save credits
aws ec2 deregister-image --image-id $(cat image.id)
