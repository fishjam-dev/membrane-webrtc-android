#!/bin/bash
brew install ktlint
ktlint installGitPreCommitHook
chmod +x ./.git/hooks/pre-commit
