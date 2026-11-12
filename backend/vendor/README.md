# 第三方源码：adb_shell 0.4.4 + rsa 4.9.1 + pyasn1 0.6.5

- `adb_shell/`：[JeffLIrion/adb_shell](https://github.com/JeffLIrion/adb_shell) 0.4.4 的原样
  拷贝（Apache-2.0，见 `adb_shell/LICENSE`），只删掉了 tests/ 和打包文件。
- `rsa/`：[stuvel/python-rsa](https://github.com/stuvel/python-rsa) 4.9.1（Apache-2.0，
  见 `LICENSE-rsa`），`adb_shell.auth.sign_pythonrsa` 用它做 RSA 签名。
- `pyasn1/`：[etingof/pyasn1](https://github.com/pyasn1/pyasn1) 0.6.5（BSD-2-Clause，
  见 `LICENSE-pyasn1`），`adb_shell` 用它解开 adbkey 的 PKCS#8 包装。

为什么放在仓库里而不用 pip：

- 后端 Docker 镜像用 `--no-index --find-links=wheels` 离线安装；`adb-shell` 在 PyPI 上只有
  sdist，离线构建需要 setuptools 和构建隔离，镜像里不保证有；
- 不想为了一个兜底功能再给 wheelhouse 加 `cryptography`（编译 wheel）或维护额外的
  `rsa`/`pyasn1` 版本约束；
- 三个包都是纯 Python，总共约 1 MB，版本固定，避免上游停更后行为漂移。

`pyadb_client.py` 会把 `vendor/` 放在 `sys.path` 最前面，因此 `adb_shell`、`rsa`、`pyasn1`
都从仓库内解析，宿主机不需要装它们。不要 import `adb_shell.auth.keygen` 或
`sign_cryptography`，它们依赖 `cryptography`。
