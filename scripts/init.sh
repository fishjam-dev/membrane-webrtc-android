#!/bin/bash
brew install ktlint release-it
cp .githooks/* .git/hooks/
chmod +x ./.git/hooks/pre-commit
