output "repository_url" {
  description = "이미지를 올릴 주소"
  value       = aws_ecr_repository.mock.repository_url
}
