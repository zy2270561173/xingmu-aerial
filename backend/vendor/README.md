# 第三方源码：adb_shell 0.4.4

`adb_shell/` 是 [JeffLIrion/adb_shell](https://github.com/JeffLIrion/adb_shell) 0.4.4 的
原样拷贝（Apache-2.0，见 `adb_shell/LICENSE`），只删掉了 tests/ 和打包文件。

为什么放在仓库里而不用 pip：

- 后端 Docker 镜像用 `--no-index --find-links=wheels` 离线安装，而 `adb-shell` 在 PyPI
  上只有 sdist，离线构建需要 setuptools 和构建隔离，镜像里不保证有；
- 我们只需要其中的 ADB 协议和文件同步实现，不装它的 `cryptography` 可选签名器；
- 目录很小（约 100 KB），版本固定，避免上游停更后行为漂移。

签名只用 `rsa` + `pyasn1`（都是纯 Python，已列在 `requirements.txt`），对应
`adb_shell.auth.sign_pythonrsa.PythonRSASigner`；不要 import `adb_shell.auth.keygen`
或 `sign_cryptography`，它们依赖 `cryptography`。
