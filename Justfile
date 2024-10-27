app-dev:
    #!/usr/bin/env bash
    echo "Running local dev server"
    export ENV=local
    (trap 'kill 0' SIGINT; scala-cli run ./ --restart & npm --prefix ./ run tailwind:watch)

app-clean:
    scala-cli clean ./

test:
    #!/usr/bin/env bash
    echo "Running tests"
    export ENV=test
    scala-cli test ./
