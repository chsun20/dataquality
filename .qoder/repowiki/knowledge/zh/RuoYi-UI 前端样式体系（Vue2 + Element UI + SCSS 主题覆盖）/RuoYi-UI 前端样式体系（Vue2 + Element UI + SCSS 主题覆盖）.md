---
kind: frontend_style
name: RuoYi-UI 前端样式体系（Vue2 + Element UI + SCSS 主题覆盖）
category: frontend_style
scope:
    - '**'
source_files:
    - ruoyi-ui/package.json
    - ruoyi-ui/vue.config.js
    - ruoyi-ui/src/assets/styles/index.scss
    - ruoyi-ui/src/assets/styles/variables.scss
    - ruoyi-ui/src/assets/styles/element-variables.scss
    - ruoyi-ui/src/assets/styles/element-ui.scss
    - ruoyi-ui/src/assets/styles/sidebar.scss
    - ruoyi-ui/src/assets/styles/mixin.scss
    - ruoyi-ui/src/assets/styles/transition.scss
    - ruoyi-ui/src/assets/styles/btn.scss
    - ruoyi-ui/src/settings.js
---

## 1. 技术栈与构建

- 框架：Vue 2.6.12 + Vue Router 3 + Vuex 3。
- UI 组件库：**Element UI 2.15.8**，通过 `element-ui` npm 包引入。
- 预处理器：**SCSS**（依赖 `sass` 1.32.13 + `sass-loader` 10.1.1），所有样式文件均为 `.scss`。
- 构建工具：`@vue/cli-service` 4.4.6，自定义配置集中在 `ruoyi-ui/vue.config.js`。
- 图标方案：`svg-sprite-loader` 将 `src/assets/icons` 下的 SVG 编译为 sprite symbol，symbolId 格式为 `icon-[name]`。
- CSS 输出：开发环境使用 `outputStyle: "expanded"`（见 `vue.config.js` 的 `css.loaderOptions.sass`），生产环境启用 gzip 压缩（`CompressionPlugin`）。

## 2. 核心文件与目录

- `ruoyi-ui/src/assets/styles/`：全局样式入口与主题变量集中地。
  - `index.scss`：样式总入口，按顺序 import variables → mixin → transition → element-ui → sidebar → btn，并定义 body、html、`.app-container`、`.sub-navbar`、`.filter-container` 等全局类。
  - `variables.scss`：项目级 SCSS 变量（颜色 `$blue/$red/$green/$tiffany/$yellow`、菜单背景 `$base-menu-background:#304156`、侧边栏宽度 `$base-sidebar-width:200px` 等），并通过 `:export` 暴露给 JS。
  - `element-variables.scss`：覆盖 Element UI 主题变量（$--color-primary:#1890ff、$--button-font-weight:400、$--border-color-*、$--table-border），再 `@import "~element-ui/packages/theme-chalk/src/index"` 重新编译主题；同时 `:export { theme: $--color-primary }` 导出主色。
  - `element-ui.scss`：针对 Element UI 组件的局部覆盖（面包屑字体权重、上传控件隐藏 input、el-dialog 居中、el-range-editor 修复、el-dropdown-menu a 块级化等）。
  - `sidebar.scss`：侧边栏布局样式，基于 `$base-sidebar-width` 控制 main-container margin-left，支持 `theme-dark` / `theme-light` 两种菜单主题，以及 `.mobile` 响应式折叠（translate3d 滑出）。
  - `mixin.scss`、`transition.scss`、`btn.scss`：混入、过渡动画、按钮复用样式。
- `ruoyi-ui/src/settings.js`：运行时 UI 开关（`sideTheme: 'theme-dark'`、`tagsView`、`fixedHeader`、`sidebarLogo`、`topNav`、`dynamicTitle`、`errorLog`）。
- `ruoyi-ui/vue.config.js`：webpack 链式配置，包含 svg-sprite-loader、splitChunks（单独拆分 `chunk-elementUI`）、ScriptExtHtmlWebpackPlugin inline runtimeChunk、gzip 压缩等。

## 3. 架构与设计约定

- **主题变量分层**：Element UI 自身变量在 `element-variables.scss` 中覆盖，项目业务变量在 `variables.scss` 中声明，两者都通过 `:export` 暴露给 JS（前者导出 `theme`，后者导出 menuColor/menuBackground/sideBarWidth 等），实现 SCSS ↔ JS 的双向共享。
- **样式入口单一**：所有全局样式由 `index.scss` 统一 import，组件内样式应写在 `.vue` 文件的 `<style scoped>` 中，避免污染全局命名空间。
- **Element UI 二次封装策略**：不直接修改 node_modules，而是通过覆盖 SCSS 变量 + 在 `element-ui.scss` 中追加选择器的方式定制组件外观。
- **侧边栏布局模式**：固定左侧 `sidebar-container`（默认 200px），main 区域通过 `margin-left` 跟随；收起态切换为 54px，移动端通过 `.mobile` 类配合 translate3d 做抽屉式滑出。
- **SVG 图标规范**：业务图标放入 `src/assets/icons`，由 svg-sprite-loader 生成 `icon-<文件名>` 符号，组件中以 `<svg-icon icon-class="xxx"/>` 引用。

## 4. 约定与约束

- **SCSS 是唯一样式语言**：仓库中所有样式文件均为 `.scss`，无原生 `.css` 或 Tailwind 配置。
- **Element UI 版本锁定**：`package.json` 固定 `element-ui: 2.15.8`，升级需同步检查 `element-variables.scss` 中的变量名是否仍有效。
- **主题色主色调**：主色 `$--color-primary` 固定为 `#1890ff`（蓝色系），如需换肤需修改该变量并重新编译 Element UI 主题。
- **侧边栏宽度变量化**：所有侧边栏相关尺寸引用 `$base-sidebar-width`（默认 200px），禁止硬编码像素值。
- **移动端响应式**：仅通过 `.mobile` 类名切换布局，未使用媒体查询断点；新增页面若需适配移动端，应沿用此模式。
- **构建产物**：静态资源输出到 `dist/static`，生产构建关闭 source map（`productionSourceMap: false`），并对 js/css/html 启用 gzip 压缩（minRatio 0.8）。
- **lint 规则**：ESLint 通过 `.eslintrc.js` 校验 `.js/.vue`，pre-commit 钩子通过 lint-staged 执行 `eslint --fix`。