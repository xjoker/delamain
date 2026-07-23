"""内部 JADX token 解析优先级回归测试。

潜伏 bug（Yuki 记录 2026-07-23）：网关侧曾用 `env > config > CLI` 解析
`DELAMAIN_AUTH_TOKEN`，而 Java 侧（Main.java）用 `CLI > env`。融合容器的
docker/entrypoint.sh 只给两侧传相同的 `--auth-token`（CLI）、不设该 env，
所以默认场景两侧一致、故障潜伏。一旦 operator 按（当时的）文档给容器额外设置
`DELAMAIN_AUTH_TOKEN`，网关会切到该 env 值、Java 仍用 CLI 值 → 握手 token 不
一致 → 每次后端调用都 401。

修复：网关对齐 Java，改为 CLI > config > env。这里直接测试抽出的纯函数
`resolve_jadx_token`，不依赖进程启动。
"""

from main import resolve_jadx_token


def test_cli_token_wins_over_env_and_config():
    assert resolve_jadx_token(cli_token="cli-tok", config_token="cfg-tok", env_value="env-tok") == "cli-tok"


def test_config_token_wins_over_env_when_no_cli():
    assert resolve_jadx_token(cli_token=None, config_token="cfg-tok", env_value="env-tok") == "cfg-tok"


def test_env_token_used_as_last_resort_fallback():
    assert resolve_jadx_token(cli_token=None, config_token=None, env_value="env-tok") == "env-tok"


def test_empty_string_cli_and_config_treated_as_unset_falls_back_to_env():
    assert resolve_jadx_token(cli_token="", config_token="", env_value="env-tok") == "env-tok"


def test_no_token_anywhere_returns_empty_string():
    assert resolve_jadx_token(cli_token=None, config_token=None, env_value=None) == ""
