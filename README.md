# supportbot

## setup

1. Install scala-cli by following instructions in [here](https://www.scala-lang.org/download/) or [here](https://scala-cli.virtuslab.org/install).
3. Install Node through [NVM](https://github.com/nvm-sh/nvm) and run:
    ```zsh
    nvm use
    ```
2. Install [Just](https://github.com/casey/just)

## dev

```zsh
just
```

### slack setup

manifest:
```yml
_metadata:
  major_version: 1
  minor_version: 1
display_information:
  name: supportbot
  description: SupportBot App
  background_color: "#080f06"
features:
  bot_user:
    display_name: supportbot
    always_online: false
  slash_commands:
    - command: /supportbot
      url: https://principal-computed-worcester-encoding.trycloudflare.com/slack/slashCmd
      description: Ask the supportbot
      usage_hint: supportbot
      should_escape: false
oauth_config:
  scopes:
    bot:
      - commands
settings:
  org_deploy_enabled: false
  socket_mode_enabled: false
  token_rotation_enabled: false
```
