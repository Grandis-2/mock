output "role_arn" {
  description = "mock 저장소 시크릿 AWS_ROLE_ARN 에 넣을 값"
  value       = aws_iam_role.mock_ecr_push.arn
}

output "repository_url" {
  description = "이미지를 올릴 주소"
  value       = aws_ecr_repository.mock.repository_url
}
