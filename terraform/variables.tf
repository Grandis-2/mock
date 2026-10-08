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

# GitHub OIDC 토큰의 sub 는 이름이 아니라 ID 를 포함한다(use_immutable_subject).
# 조직이나 저장소 이름이 바뀌어도 ID 는 그대로라 이 값으로 고정한다.
variable "github_owner" {
  type    = string
  default = "Grandis-2"
}

variable "github_owner_id" {
  type    = string
  default = "336932992"
}

variable "github_repo" {
  type    = string
  default = "mock"
}

variable "github_repo_id" {
  type    = string
  default = "1365107413"
}

variable "release_branch" {
  description = "이미지를 올릴 수 있는 유일한 브랜치"
  type        = string
  default     = "main"
}
