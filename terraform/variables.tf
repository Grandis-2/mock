variable "aws_region" {
  description = "ECR 과 IAM 역할을 만들 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "repository_name" {
  description = "ECR 저장소 이름"
  type        = string
  default     = "nova-mock"
}
