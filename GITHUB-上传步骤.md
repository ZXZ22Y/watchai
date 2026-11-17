# 上传项目到 GitHub

项目已在本机初始化为 Git 仓库并完成首次提交（55 个文件，`local.properties` 等敏感文件已排除）。
下面的步骤任选一种即可，把你命令里的 `你的用户名` 和 `仓库名` 换成自己的。

---

## 方式一：网页创建仓库 + 本地推送（推荐）

### 1. 在 GitHub 网页新建一个空仓库
1. 打开 https://github.com/new
2. Repository name 填：`watch-ai` （或你喜欢的名字）
3. Description 可填：`腕上智析 - 小米手表真实数据健康分析 App`
4. 选 **Public**（公开）或 **Private**（私有）
5. **不要**勾选 "Add a README / .gitignore / license"（本地已经有了，避免冲突）
6. 点 **Create repository**

### 2. 在本地执行推送
在本项目目录（`xiaomi-watch-ai`）打开终端，执行：

```bash
cd /workspace/xiaomi-watch-ai

# 关联远程仓库（把 你的用户名、仓库名 换成你的）
git remote add origin https://github.com/你的用户名/仓库名.git

# 确保主分支名为 main
git branch -M main

# 推送（-u 会记住关联，之后 git push 即可）
git push -u origin main
```

首次推送会弹出登录：浏览器登录 GitHub 授权，或输入用户名 + **Personal Access Token（PAT）**
（不是账号密码）。PAT 生成地址：https://github.com/settings/tokens → Generate new token (classic)
→ 勾选 `repo` 权限 → 生成后复制保存。

看到类似 `main -> main` 的输出即上传成功，刷新网页就能看到代码。

---

## 方式二：用 GitHub CLI（需先登录）

```bash
# 1. 登录（会打开浏览器授权）
gh auth login

# 2. 创建仓库并推送（--public 可改 --private）
cd /workspace/xiaomi-watch-ai
gh repo create watch-ai --public --source=. --remote=origin --push
```

---

## 常用后续操作

```bash
# 以后改了代码，提交并推送
git add -A
git commit -m "说明这次改了什么"
git push

# 修改提交身份（如需）
git config user.name "你的名字"
git config user.email "你的邮箱"
```

---

## 上传前请确认

- [ ] `local.properties` 未被上传（本机 SDK 路径，`.gitignore` 已排除）
- [ ] 没有上传任何真实健康数据 / 诊断导出（README 已提示）
- [ ] 若仓库想公开，README 与 LICENSE（MIT）已就位
- [ ] 数据库路径、账号 ID 等隐私信息未写进代码提交（本项目代码中不含你的个人数据）

---

## 让别人也能编译

别人 clone 后需自己配置 SDK 路径：

```bash
git clone https://github.com/你的用户名/仓库名.git
cd 仓库名
echo "sdk.dir=/你的/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
```
