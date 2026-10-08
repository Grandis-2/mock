resource "aws_ecr_repository" "mock" {
  #checkov:skip=CKV_AWS_51:latest 태그를 매번 새 이미지로 옮겨야 한다
  #checkov:skip=CKV_AWS_136:AWS 관리 키(AES256)로 충분하다. KMS 키는 비용 · 관리 부담만 늘린다
  name = var.repository_name

  # latest 태그를 매번 새 이미지로 옮겨야 해서 MUTABLE 이다.
  #trivy:ignore:AVD-AWS-0031
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  # AWS 관리 키(AES256). 고객 관리 KMS 키는 쓰지 않는다.
  #trivy:ignore:AVD-AWS-0033
  encryption_configuration {
    encryption_type = "AES256"
  }
}

# 태그 없는 이미지(덮어써진 latest 의 이전 이미지 등)는 7일 뒤 지운다.
resource "aws_ecr_lifecycle_policy" "mock" {
  repository = aws_ecr_repository.mock.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Expire untagged images after 7 days"
      selection = {
        tagStatus   = "untagged"
        countType   = "sinceImagePushed"
        countUnit   = "days"
        countNumber = 7
      }
      action = { type = "expire" }
    }]
  })
}
