# 계정에 이미 있는 GitHub OIDC 공급자를 가져다 쓴다. 새로 만들면 안 된다(계정당 하나).
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

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

# GitHub Actions 가 main 에서 돌 때만 이 역할을 쓸 수 있다.
data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${var.github_owner}@${var.github_owner_id}/${var.github_repo}@${var.github_repo_id}:ref:refs/heads/${var.release_branch}",
      ]
    }
  }
}

resource "aws_iam_role" "mock_ecr_push" {
  name                 = "nova-mock-ecr"
  description          = "GitHub Actions (Grandis-2/mock, main) pushes images to nova-mock"
  assume_role_policy   = data.aws_iam_policy_document.assume.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "push" {
  # 로그인 토큰은 저장소 단위로 줄 수 없다. AWS 가 Resource "*" 만 받는다.
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPushMockOnly"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
      "ecr:DescribeImages",
      "ecr:DescribeRepositories",
    ]
    resources = [aws_ecr_repository.mock.arn]
  }
}

resource "aws_iam_role_policy" "mock_ecr_push" {
  name   = "nova-mock-ecr-push"
  role   = aws_iam_role.mock_ecr_push.id
  policy = data.aws_iam_policy_document.push.json
}
