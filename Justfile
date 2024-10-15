app-dev:
    #!/usr/bin/env bash
    echo "Running local dev server"
    export ENV=local
    (trap 'kill 0' SIGINT; scala-cli run ./ --restart & npm --prefix ./ run tailwind:watch)
