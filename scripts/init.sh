#!/bin/bash
brew install ktlint release-it
ktlint installGitPreCommitHook
chmod +x ./.git/hooks/pre-commit
