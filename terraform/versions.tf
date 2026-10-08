terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.aws_region

  # 이 코드로 만든 리소스에 자동으로 붙는 태그. 콘솔에서 누가 만들었는지 알 수 있다.
  default_tags {
    tags = {
      Project   = "nova"
      Component = "mock"
      ManagedBy = "terraform"
    }
  }
}
