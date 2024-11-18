app-dev:
    #!/usr/bin/env bash
    echo "Running local dev server"
    export ENV=Local
    (
        trap 'kill 0' SIGINT; 
        scala-cli run . --restart & 
        npm run tailwind:watch --workspace app &
        npm run esbuild:watch --workspace app
    )

app-infra-fresh:
    docker-compose -f ./docker-compose.yml up --pull=always

app-clean:
    scala-cli clean .

test:
    #!/usr/bin/env bash
    echo "Running tests"
    export ENV=Test
    scala-cli test .
