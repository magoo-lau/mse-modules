<template>
  <div class="app">
    <!-- 登录页（两段式：账号密码 + 双因子） -->
    <div v-if="!state.loggedIn" class="login">
      <h2>ai-mse 聊天 · Web 客户端</h2>
      <p class="hint">登录走 ai-mse 账号体系（租户编码 + 账号密码），IM 凭证由 mse-im 签发</p>
      <!-- 第一段：账号密码（+ 风控浮出的图形验证码） -->
      <template v-if="!twoFactor.active">
        <label>租户编码<input v-model="form.tenantCode" placeholder="如 imreg" /></label>
        <label>账号<input v-model="form.username" placeholder="用户名" /></label>
        <label>密码<input v-model="form.password" type="password" placeholder="密码" @keyup.enter="doLogin" /></label>
        <label v-if="captcha.required">验证码
          <span class="captcha-row">
            <input v-model="form.captchaCode" placeholder="图中字符" maxlength="6" @keyup.enter="doLogin" />
            <img v-if="captcha.image" class="captcha-img" :src="captcha.image" alt="图形验证码" title="看不清？点击刷新" @click="refreshCaptcha" />
          </span>
        </label>
        <button :disabled="state.busy" @click="doLogin">{{ state.busy ? '登录中…' : '登录并连接 IM' }}</button>
      </template>
      <!-- 第二段：双因子验证（未绑定先扫码；动态码支持 TOTP / 邮箱两通道） -->
      <template v-else>
        <template v-if="twoFactor.enroll">
          <p class="hint">首次登录需绑定验证器 App（如 Google Authenticator），扫码或手输密钥后，输入 6 位动态码完成绑定并登录</p>
          <img v-if="twoFactor.qrImage" class="totp-qr" :src="twoFactor.qrImage" alt="TOTP 绑定二维码" />
          <p v-if="twoFactor.secret" class="hint">手输密钥：{{ twoFactor.secret }}</p>
        </template>
        <p v-if="!twoFactor.enroll" class="hint">密码校验已通过，请输入验证器 App 中的 6 位动态码完成登录</p>
        <label>动态码<input v-model="twoFactor.code" maxlength="6" placeholder="6 位动态码" @keyup.enter="doSecondFactor" /></label>
        <button :disabled="state.busy" @click="doSecondFactor">{{ state.busy ? '验证中…' : '验证并登录' }}</button>
        <button v-if="!twoFactor.enroll && twoFactor.emailAvailable" class="ghost" :disabled="twoFactor.sending || twoFactor.countdown > 0" @click="sendEmailCode">
          {{ twoFactor.countdown > 0 ? `验证码已发送（${twoFactor.countdown}s 后可重发）` : twoFactor.emailChannel
            ? `重发邮箱验证码${twoFactor.maskedEmail ? `（${twoFactor.maskedEmail}）` : ''}`
            : `改用邮箱验证码${twoFactor.maskedEmail ? `（${twoFactor.maskedEmail}）` : ''}` }}
        </button>
        <button class="ghost" :disabled="state.busy" @click="backToForm">返回重填账号</button>
      </template>
      <p v-if="state.error" class="error">{{ state.error }}</p>
      <p class="hint">状态：{{ state.status }}</p>
      <!-- 桌面端：服务器地址入口（返回服务器设置界面，Electron 壳接管呈现） -->
      <div v-if="hasDesktop" class="setup-row">
        <span class="hint">服务器：{{ remoteBase || '（开发模式：vite 代理）' }}</span>
        <button class="ghost setup-link" @click="openServerSetupWindow">修改…</button>
      </div>
    </div>

    <!-- 主界面：左图标导航栏 + 中列表面板 + 右内容区（企业微信式三区） -->
    <div v-else class="main">
      <!-- 图标导航栏 -->
      <aside class="rail">
        <div class="me clickable" :title="`${state.nickname}（${state.userID}）\n${state.status}`" @click="openProfileSelf">
          <img v-if="state.faceURL" class="me-face" :src="assetUrl(state.faceURL)" alt="" />
          <span v-else class="me-face char">{{ (state.nickname || state.userID || '?').charAt(0) }}</span>
          <i class="me-dot" :class="{ on: state.connected }"></i>
        </div>
        <nav class="rail-nav">
          <button class="rail-btn" :class="{ active: state.tab === 'chat' }" title="会话" @click="switchTab('chat')">
            <svg viewBox="0 0 24 24">
              <path d="M12 3C7.03 3 3 6.58 3 11c0 2.27 1.07 4.32 2.8 5.79-.06 1.13-.42 2.62-1.63 3.62 2 .18 3.63-.62 4.68-1.36.99.3 2.05.45 3.15.45 4.97 0 9-3.58 9-8.5S16.97 3 12 3z" />
            </svg>
            <i v-if="state.unreadTotal > 0" class="badge">{{ state.unreadTotal > 99 ? '99+' : state.unreadTotal }}</i>
          </button>
          <button class="rail-btn" :class="{ active: state.tab === 'contacts' }" title="联系人" @click="switchTab('contacts')">
            <svg viewBox="0 0 24 24">
              <path d="M12 11.5a3.75 3.75 0 1 0-3.75-3.75A3.75 3.75 0 0 0 12 11.5zm0 2.25c-3.75 0-7.5 1.88-7.5 4.5V20h15v-1.75c0-2.62-3.75-4.5-7.5-4.5z" />
            </svg>
          </button>
          <button class="rail-btn" :class="{ active: state.tab === 'workbench' }" title="工作台" @click="switchTab('workbench')">
            <svg viewBox="0 0 24 24">
              <rect x="3.5" y="3.5" width="7.5" height="7.5" rx="2" />
              <rect x="13" y="3.5" width="7.5" height="7.5" rx="2" />
              <rect x="3.5" y="13" width="7.5" height="7.5" rx="2" />
              <rect x="13" y="13" width="7.5" height="7.5" rx="2" />
            </svg>
          </button>
        </nav>
        <div class="rail-bottom">
          <button class="rail-btn" :title="state.theme === 'dark' ? '切换为浅色' : '切换为深色'" @click="toggleTheme">
            <svg viewBox="0 0 24 24">
              <!-- 图标指向切换目标：暗色显太阳（点击转浅色），浅色显月亮（点击转深色） -->
              <path v-if="state.theme === 'dark'" d="M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zM2 13h2c.55 0 1-.45 1-1s-.45-1-1-1H2c-.55 0-1 .45-1 1s.45 1 1 1zm18 0h2c.55 0 1-.45 1-1s-.45-1-1-1h-2c-.55 0-1 .45-1 1s.45 1 1 1zM11 2v2c0 .55.45 1 1 1s1-.45 1-1V2c0-.55-.45-1-1-1s-1 .45-1 1zm0 18v2c0 .55.45 1 1 1s1-.45 1-1v-2c0-.55-.45-1-1-1s-1 .45-1 1zM5.99 4.58c-.39-.39-1.03-.39-1.41 0-.39.39-.39 1.03 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0s.39-1.03 0-1.41L5.99 4.58zm12.37 12.37c-.39-.39-1.03-.39-1.41 0-.39.39-.39 1.03 0 1.41l1.06 1.06c.39.39 1.03.39 1.41 0 .39-.39.39-1.03 0-1.41l-1.06-1.06zm1.06-10.96c.39-.39.39-1.03 0-1.41-.39-.39-1.03-.39-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06zM7.05 18.36c.39-.39.39-1.03 0-1.41-.39-.39-1.03-.39-1.41 0l-1.06 1.06c-.39.39-.39 1.03 0 1.41s1.03.39 1.41 0l1.06-1.06z" />
              <path v-else d="M20.3 14.6A8.5 8.5 0 0 1 9.4 3.7 8.5 8.5 0 1 0 20.3 14.6z" />
            </svg>
          </button>
          <button v-if="hasDesktop" class="rail-btn" :class="{ active: state.settingsOpen }" title="设置" @click="state.settingsOpen = !state.settingsOpen">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.01a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.01a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.01a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" /></svg>
          </button>
        </div>
      </aside>

      <!-- 列表面板 -->
      <section class="panel">
        <div class="searchbar">
          <svg viewBox="0 0 24 24">
            <path d="M10.5 3a7.5 7.5 0 1 0 4.55 13.46l4.24 4.25 1.42-1.42-4.25-4.24A7.5 7.5 0 0 0 10.5 3zm0 2a5.5 5.5 0 1 1-5.5 5.5A5.5 5.5 0 0 1 10.5 5z" />
          </svg>
          <input v-model="state.filter" :placeholder="filterPlaceholder" />
          <button v-if="state.tab === 'chat'" class="search-msg-btn" title="搜索消息" @click="state.searchVisible = true">🔍</button>
        </div>

        <!-- 会话列表 -->
        <div v-show="state.tab === 'chat'" class="list">
          <div v-for="c in convFiltered" :key="c.conversationID" class="conv"
               :class="{ active: state.activeConv?.conversationID === c.conversationID }"
               @click="openConv(c)" @contextmenu.prevent="openConvMenu($event, c)">
            <img v-if="c.faceURL" class="face" :src="assetUrl(c.faceURL)" alt="" />
            <span v-else class="face char" :class="{ bot: isBotConv(c) }">{{ isBotConv(c) ? '🤖' : (c.showName || c.userID || c.groupID || '?').charAt(0) }}</span>
            <div class="conv-main">
              <div class="conv-row">
                <span class="conv-name">
                  <i v-if="c.isPinned" class="pin-mark" title="置顶">📌</i>
                  {{ c.showName || c.userID || c.groupID }}
                </span>
                <span class="conv-time">{{ fmtTime(c.latestMsgSendTime, true) }}</span>
              </div>
              <div class="conv-row">
                <span class="conv-preview">{{ convPreview(c) }}</span>
                <i v-if="c.recvMsgOpt === 2" class="mute-mark" title="免打扰">🔕</i>
                <i v-if="c.unreadCount > 0" class="badge" :class="{ gray: c.recvMsgOpt === 2 }">{{ c.unreadCount > 99 ? '99+' : c.unreadCount }}</i>
              </div>
            </div>
          </div>
          <div v-if="!convFiltered.length" class="empty-mini">
            {{ state.filter ? '无匹配会话' : '暂无会话——去「联系人」发起单聊，或让对方先给你发一条消息' }}
          </div>
        </div>

        <!-- 联系人：组织架构树 + 机器人分组 + 我的群聊 -->
        <div v-show="state.tab === 'contacts'" class="list">
          <div class="newchat-bar">
            <button class="link-btn" @click="openGroupPicker">＋ 发起群聊</button>
          </div>
          <template v-if="!state.filter">
            <template v-for="row in contactRows" :key="row.key">
              <div v-if="row.kind === 'dept'" class="dept-row" :style="{ paddingLeft: 8 + row.level * 16 + 'px' }"
                   @click="toggleDept(row.id!)">
                <i class="arrow" :class="{ open: isDeptOpen(row.id!) }">
                  <svg viewBox="0 0 16 16"><path d="M6 3.5 10.5 8 6 12.5" /></svg>
                </i>
                <span class="dept-nm">{{ row.name }}</span>
                <span class="dept-cnt">({{ row.count }})</span>
              </div>
              <div v-else class="item" :style="{ paddingLeft: 8 + row.level * 16 + 'px' }" @click="openUser(row.user!)">
                <img v-if="row.user!.avatar" class="face click" :src="assetUrl(row.user!.avatar)" alt="" @click.stop="openProfile(String(row.user!.userId))" />
                <span v-else class="face char click" @click.stop="openProfile(String(row.user!.userId))">{{ row.user!.nickname.charAt(0) }}</span>
                <span class="item-name">{{ row.user!.nickname }}</span>
              </div>
            </template>
            <div v-if="!contactRows.length" class="empty-mini">通讯录为空（部门/用户来自系统管理）</div>
            <div class="group-head" @click="toggleFold('bots')">
              <i class="arrow" :class="{ open: !state.folded.bots }">
                <svg viewBox="0 0 16 16"><path d="M6 3.5 10.5 8 6 12.5" /></svg>
              </i>
              <span class="head-nm">机器人</span>
              <span class="cnt">({{ state.contacts.bots.length }})</span>
            </div>
            <template v-if="!state.folded.bots">
              <div v-for="b in state.contacts.bots" :key="b.botUserId" class="item" @click="openBot(b)">
                <img v-if="b.faceUrl" class="face click" :src="assetUrl(b.faceUrl)" alt="" @click.stop="openBotProfile(b)" />
                <span v-else class="face char bot click" @click.stop="openBotProfile(b)">🤖</span>
                <span class="item-name">{{ b.nickname }}</span>
                <span class="item-tag">机器人</span>
              </div>
              <div v-if="!state.contacts.bots.length" class="empty-mini">暂无机器人——可在管理端「机器人」页添加</div>
            </template>
          </template>
          <template v-else>
            <div v-for="u in matchedUsers" :key="'su' + u.userId" class="item" @click="openUser(u)">
              <img v-if="u.avatar" class="face click" :src="assetUrl(u.avatar)" alt="" @click.stop="openProfile(String(u.userId))" />
              <span v-else class="face char click" @click.stop="openProfile(String(u.userId))">{{ u.nickname.charAt(0) }}</span>
              <span class="item-name">{{ u.nickname }}</span>
              <span class="item-tag">{{ deptNameOf(u.deptId) }}</span>
            </div>
            <div v-for="b in matchedBots" :key="'sb' + b.botUserId" class="item" @click="openBot(b)">
              <img v-if="b.faceUrl" class="face click" :src="assetUrl(b.faceUrl)" alt="" @click.stop="openBotProfile(b)" />
              <span v-else class="face char bot click" @click.stop="openBotProfile(b)">🤖</span>
              <span class="item-name">{{ b.nickname }}</span>
              <span class="item-tag">机器人</span>
            </div>
            <div v-if="!matchedUsers.length && !matchedBots.length" class="empty-mini">无匹配联系人</div>
          </template>
          <div class="group-head" @click="toggleFold('groups')">
            <i class="arrow" :class="{ open: !state.folded.groups }">
              <svg viewBox="0 0 16 16"><path d="M6 3.5 10.5 8 6 12.5" /></svg>
            </i>
            <span class="head-nm">我的群聊</span>
            <span class="cnt">({{ groupsFiltered.length }})</span>
          </div>
          <template v-if="!state.folded.groups">
            <div v-for="g in groupsFiltered" :key="g.groupID" class="item" @click="openGroup(g)">
              <img v-if="g.groupFaceURL" class="face" :src="assetUrl(g.groupFaceURL)" alt="" />
              <span v-else class="face char group">{{ (g.groupName || g.groupID || '?').charAt(0) }}</span>
              <span class="item-name">{{ g.groupName || g.groupID }}</span>
            </div>
            <div v-if="!groupsFiltered.length" class="empty-mini">{{ state.filter ? '无匹配群聊' : '暂无群聊' }}</div>
          </template>
        </div>

        <!-- 工作台宫格：分组样式对齐移动端基站（分组标题 + 子组标题 + 4 列彩色圆角图标） -->
        <div v-show="state.tab === 'workbench'" class="list grid">
          <template v-for="g in wbGroups" :key="g.group">
            <div class="grid-group">{{ g.group }}</div>
            <template v-for="s in g.subs" :key="g.group + s.sub">
              <div v-if="s.sub" class="grid-sub">{{ s.sub }}</div>
              <div class="grid-cells">
                <button v-for="e in s.items" :key="e.path" class="cell"
                        :class="{ active: state.activeEntry?.path === e.path }" :disabled="e.opening" @click="openEntry(e)">
                  <span class="cell-icon" :style="{ background: iconBg(e) }">
                    <img v-if="isImgIcon(e.icon)" :src="assetUrl(e.icon)" alt="" />
                    <svg v-else-if="!e.icon || e.icon === DEFAULT_ICON" viewBox="0 0 24 24" fill="currentColor">
                      <template v-if="e.entryType === 1">
                        <path d="M10.5 13.5a4 4 0 0 0 5.66 0l3.18-3.18a4 4 0 1 0-5.66-5.66l-1.42 1.42 1.42 1.41 1.41-1.41a2 2 0 1 1 2.83 2.83l-3.18 3.18a2 2 0 0 1-2.83 0z" />
                        <path d="M13.5 10.5a4 4 0 0 0-5.66 0l-3.18 3.18a4 4 0 1 0 5.66 5.66l1.42-1.42-1.42-1.41-1.41 1.41a2 2 0 1 1-2.83-2.83l3.18-3.18a2 2 0 0 1 2.83 0z" />
                      </template>
                      <template v-else>
                        <rect x="3.5" y="3.5" width="7.5" height="7.5" rx="2" />
                        <rect x="13" y="3.5" width="7.5" height="7.5" rx="2" />
                        <rect x="3.5" y="13" width="7.5" height="7.5" rx="2" />
                        <rect x="13" y="13" width="7.5" height="7.5" rx="2" />
                      </template>
                    </svg>
                    <component v-else-if="epIconComp(e.icon)" :is="epIconComp(e.icon)" />
                    <span v-else class="cell-emoji">{{ e.icon }}</span>
                  </span>
                  <span class="cell-name">{{ e.name }}</span>
                </button>
              </div>
            </template>
          </template>
          <div v-if="!wbGroups.length" class="empty-mini">
            {{ state.filter ? '无匹配应用' : '该账号可见的页面菜单为空（菜单未在套餐勾选或未授权），或在「即时通讯 → 工作台配置」中被隐藏' }}
          </div>
        </div>
      </section>

      <!-- 内容区 -->
      <section class="content">
        <!-- 会话 -->
        <div v-show="state.tab === 'chat'" class="pane">
          <template v-if="state.activeConv">
            <div class="conv-head">
              <span class="conv-head-name clickable"
                    :title="state.activeConv.groupID ? '查看群资料' : '查看资料'"
                    @click="onConvHeadClick">
                {{ state.activeConv.showName || state.activeConv.userID || state.activeConv.groupID }}
                <i v-if="isTyping" class="typing-hint">对方正在输入…</i>
                <i v-else-if="peerOnline" class="online-hint">在线</i>
              </span>
              <span class="conv-head-actions">
                <button class="gh-more" title="搜索消息" @click="state.searchVisible = true">🔍</button>
                <button v-if="state.activeConv.groupID" class="gh-more" title="群资料 / 管理" @click="openGroupDrawer">…</button>
              </span>
            </div>

            <MessageList ref="msgList" :messages="state.messages" :me-id="state.userID"
                         :has-more="!state.historyEnd" :loading-more="state.loadingMore"
                         :selecting="state.selectMode" :selected-ids="state.selectedIds" :progress-map="state.progressMap"
                         @load-more="loadMore" @menu="openMsgMenu"
                         @view="(u: string) => openViewer({ url: u, mode: 'image' })"
                         @play="(u: string) => openViewer({ url: u, mode: 'video' })"
                         @preview="state.mergePreviewMsg = $event" @locate="locateMsg" @open-card="openCard"
                         @resend="resend" @toggle-select="toggleSelect" @show-profile="onSenderProfile" />

            <!-- 多选操作条 -->
            <div v-if="state.selectMode" class="sel-bar">
              <span class="hint">已选 {{ state.selectedIds.length }} 条</span>
              <button class="primary sm" :disabled="state.selectedIds.length < 2" @click="forwardSelected(true)">合并转发</button>
              <button class="sm" :disabled="!state.selectedIds.length" @click="forwardSelected(false)">逐条转发</button>
              <button class="sm" @click="exitSelect">取消</button>
            </div>
            <template v-else>
              <Composer :me-id="state.userID" :members="state.members" :is-group="!!state.activeConv.groupID"
                        v-model:quote-msg="state.quoteMsg" :draft="state.draft" :rtc-enabled="state.rtc.enabled"
                        :can-call="!state.activeConv.groupID"
                        @update:draft="onDraft" @send-text="sendText" @send-image="sendImage" @send-video="sendVideo"
                        @send-file="sendFile" @send-sound="sendSound" @pick-card="openCardPicker"
                        @compose-link="openLinkForm"
                        @call="startCall" @typing-focus="onTypingFocus" @typing-blur="reportTyping(false)" />
            </template>
          </template>
          <div v-else class="placeholder">
            <svg viewBox="0 0 24 24"><path d="M12 3C7.03 3 3 6.58 3 11c0 2.27 1.07 4.32 2.8 5.79-.06 1.13-.42 2.62-1.63 3.62 2 .18 3.63-.62 4.68-1.36.99.3 2.05.45 3.15.45 4.97 0 9-3.58 9-8.5S16.97 3 12 3z" /></svg>
            <p>选择左侧会话，开始聊天</p>
          </div>
        </div>

        <!-- 联系人 -->
        <div v-show="state.tab === 'contacts'" class="pane">
          <div class="placeholder">
            <svg viewBox="0 0 24 24"><path d="M12 11.5a3.75 3.75 0 1 0-3.75-3.75A3.75 3.75 0 0 0 12 11.5zm0 2.25c-3.75 0-7.5 1.88-7.5 4.5V20h15v-1.75c0-2.62-3.75-4.5-7.5-4.5z" /></svg>
            <p>点击左侧联系人，直接发起会话</p>
          </div>
        </div>

        <!-- 工作台：右侧内嵌应用（弹窗入口直接开新窗口，不经此面板） -->
        <div v-show="state.tab === 'workbench'" class="pane">
          <div v-if="state.activeEntry" class="embed">
            <div class="embed-bar">
              <b>{{ state.activeEntry.name }}</b>
              <span class="hint">{{ state.activeEntry.path }}</span>
              <button v-if="state.activeEntry.entryType === 0" class="mini-btn" @click="openShare">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="5" r="3" /><circle cx="6" cy="12" r="3" /><circle cx="18" cy="19" r="3" /><line x1="8.59" y1="13.51" x2="15.42" y2="17.49" /><line x1="15.41" y1="6.51" x2="8.59" y2="10.49" /></svg>
                分享
              </button>
              <button class="mini-btn" :disabled="state.entryBusy" @click="openNewWindow">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" /><polyline points="15 3 21 3 21 9" /><line x1="10" y1="14" x2="21" y2="3" /></svg>
                {{ state.entryBusy ? '签发中…' : '新窗口打开' }}
              </button>
            </div>
            <iframe ref="embedFrame" class="embed-frame" :src="state.entryUrl" :title="state.activeEntry.name"></iframe>
          </div>
          <div v-else class="placeholder">
            <svg viewBox="0 0 24 24">
              <rect x="3.5" y="3.5" width="7.5" height="7.5" rx="2" />
              <rect x="13" y="3.5" width="7.5" height="7.5" rx="2" />
              <rect x="3.5" y="13" width="7.5" height="7.5" rx="2" />
              <rect x="13" y="13" width="7.5" height="7.5" rx="2" />
            </svg>
            <p>点击左侧应用，在此直接打开（免登录；弹窗入口将打开新窗口）</p>
          </div>
        </div>
      </section>
    </div>

    <!-- 发起群聊选择器（成员树 + 机器人多选） -->
    <PickerModal :visible="state.showPicker" title="发起群聊" :depts="state.contacts.depts"
                 :users="state.contacts.users" :bots="state.contacts.bots" with-name
                 :loading="state.contactsLoading"
                 @close="state.showPicker = false" @confirm="doCreateGroup" />

    <!-- 名片选择器（单选） -->
    <PickerModal :visible="state.showCardPicker" title="选择名片" :depts="state.contacts.depts"
                 :users="state.contacts.users" :bots="state.contacts.bots" single
                 :loading="state.contactsLoading"
                 @close="state.showCardPicker = false" @confirm="sendCard" />

    <!-- 群资料抽屉（成员/邀请/群管理） -->
    <GroupDrawer :visible="state.drawerVisible" :group-id="state.drawerGroupId" :group-name="state.drawerGroupName"
                 :me-id="state.userID" :sdk="sdk" :depts="state.contacts.depts"
                 :users="state.contacts.users" :bots="state.contacts.bots"
                 :dept-group-ids="state.contacts.deptGroupIds" :contacts-loading="state.contactsLoading"
                 @close="state.drawerVisible = false" @changed="onGroupChanged" @renamed="onGroupRenamed"
                 @avatar-changed="onGroupAvatarChanged"
                 @show-profile="onDrawerMember" />

    <!-- 用户/机器人/本人资料卡 -->
    <UserProfileDrawer :visible="state.profile.visible" :mode="state.profile.mode"
                       :user-id="state.profile.userId" :bot="state.profile.bot" :me-face-url="state.faceURL"
                       @close="state.profile.visible = false" @chat="profileChat" @avatar-changed="onAvatarChanged" />

    <!-- 转发目标选择器 -->
    <ForwardPickerModal :visible="!!forwardState" title="转发到" :conversations="state.conversations"
                        :depts="state.contacts.depts" :users="state.contacts.users" :bots="state.contacts.bots" :groups="state.groups"
                        :busy="forwardBusy" @close="forwardState = null" @send="doForward" />

    <!-- 页面分享选择器（多选联系人/群聊，联系人按部门树展示；机器人不参与页面分享） -->
    <ForwardPickerModal :visible="state.showSharePicker" title="分享页面给" :conversations="state.conversations"
                        :depts="state.contacts.depts" :users="state.contacts.users" :bots="[]" :groups="state.groups" multiple
                        :busy="state.shareBusy" @close="state.showSharePicker = false" @send="doSharePage" />

    <!-- 合并转发预览 -->
    <MergePreviewModal :visible="!!state.mergePreviewMsg" :messages="state.mergePreviewMsg?.mergeElem?.multiMessage || []"
                       :me-id="state.userID" @close="state.mergePreviewMsg = null" />

    <!-- 图片/视频查看器 -->
    <ImageViewer :visible="!!state.viewer" :url="state.viewer?.url || ''" :mode="state.viewer?.mode || 'image'"
                 @close="state.viewer = null" />

    <!-- 消息搜索 -->
    <SearchPanel :visible="state.searchVisible" :sdk="sdk" @close="state.searchVisible = false" @open="locateSearchHit" />

    <!-- 链接卡片表单 -->
    <div v-if="state.linkForm" class="lf-mask" @click.self="state.linkForm = null">
      <div class="lf">
        <div class="lf-head">发送链接卡片</div>
        <label>标题<input v-model="state.linkForm.title" maxlength="64" placeholder="卡片标题" /></label>
        <label>描述<input v-model="state.linkForm.desc" maxlength="128" placeholder="一句话描述（可选）" /></label>
        <label>链接<input v-model="state.linkForm.url" placeholder="https://…" /></label>
        <label>缩略图 URL<input v-model="state.linkForm.thumbUrl" placeholder="https://…（可选）" /></label>
        <p v-if="state.linkForm.error" class="error">{{ state.linkForm.error }}</p>
        <div class="lf-foot">
          <button @click="state.linkForm = null">取消</button>
          <button class="primary" @click="sendLink">发送</button>
        </div>
      </div>
    </div>

    <!-- 消息右键菜单 -->
    <ContextMenu :visible="!!msgMenu" :x="msgMenu?.x || 0" :y="msgMenu?.y || 0" :items="msgMenuItems"
                 @close="msgMenu = null" @select="onMsgMenu" />
    <!-- 会话右键菜单 -->
    <ContextMenu :visible="!!convMenu" :x="convMenu?.x || 0" :y="convMenu?.y || 0" :items="convMenuItems"
                 @close="convMenu = null" @select="onConvMenu" />

    <!-- 桌面端设置（满屏设置页：服务器地址 / 消息通知 / 退出登录；Esc 或右上 ✕ 关闭） -->
    <div v-if="state.settingsOpen" class="set-mask">
      <div class="set-head">
        <span class="set-title">设置</span>
        <button class="set-close" title="关闭" @click="state.settingsOpen = false">✕</button>
      </div>
      <div class="set-body">
        <div class="set-card">
          <div class="set-sec-title">服务器</div>
          <div class="set-row">
            <span class="set-label">当前地址</span>
            <span class="set-val" :title="remoteBase">{{ remoteBase || '（开发模式：vite 代理）' }}</span>
          </div>
          <button class="set-btn" @click="openServerSetupWindow">修改服务器地址…</button>
        </div>
        <div class="set-card">
          <div class="set-sec-title">通知</div>
          <div class="set-row">
            <span class="set-label">消息通知</span>
            <label class="set-switch"><input v-model="notifyEnabled" type="checkbox" /><i></i></label>
          </div>
        </div>
        <button class="set-btn danger set-logout" @click="logout">退出登录</button>
      </div>
    </div>

    <!-- 全局轻提示（操作成败反馈；连接状态走状态栏 tooltip 不在此列） -->
    <Toasts />

    <!-- 音视频通话 -->
    <CallOverlay v-if="callMgr" :call="callMgr" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { CbEvents, WasmSdk } from '@openim/wasm-client-sdk'
import { createIM, imLogin, type ImSession } from './im'
import * as mse from './mse'
import PickerModal from './components/PickerModal.vue'
import GroupDrawer from './components/GroupDrawer.vue'
import UserProfileDrawer from './components/UserProfileDrawer.vue'
import MessageList from './chat/MessageList.vue'
import Composer from './chat/Composer.vue'
import ContextMenu, { type CtxItem } from './chat/ContextMenu.vue'
import ForwardPickerModal from './chat/ForwardPickerModal.vue'
import MergePreviewModal from './chat/MergePreviewModal.vue'
import ImageViewer from './chat/ImageViewer.vue'
import SearchPanel from './chat/SearchPanel.vue'
import CallOverlay from './chat/CallOverlay.vue'
import Toasts from './components/Toasts.vue'
import { createCallManager, type CallManager } from './chat/call'
import { buildFileMessage, buildImageMessage, buildSoundMessage, buildVideoMessage } from './chat/media'
import { isCallSignal, parseCustom } from './chat/custom'
import { summaryOf } from './chat/summary'
import { toast } from './chat/toast'
import { assetUrl, copyText, epIconComp, fmtTime, isBotUserId } from './chat/util'
import { hasDesktop, notifyDesktop, onNotifyClick, openServerSetup as openServerSetupWindow, remoteBase, reportBadge } from './desktop'

const msgList = ref<InstanceType<typeof MessageList>>()
const form = reactive({ tenantCode: 'imreg', username: '', password: '', captchaCode: '' })

/** 图形验证码挑战（risk 模式失败计数达标自动浮出，与基座登录页同款） */
const captcha = reactive<mse.CaptchaChallenge>({ required: false, captchaId: null, image: null })
/** 双因子阶段状态（tempToken 5 分钟有效，限 5 次尝试；enroll=true 首次登录先扫码绑定） */
const twoFactor = reactive({
  active: false, tempToken: '', enroll: false,
  emailAvailable: false, emailChannel: false, maskedEmail: '',
  qrImage: '', secret: '', code: '',
  sending: false, countdown: 0,
})
let emailTimer: ReturnType<typeof setInterval> | null = null

const state = reactive({
  loggedIn: false, busy: false, error: '', status: '未连接', connected: false,
  theme: 'light' as 'light' | 'dark',
  tab: 'chat' as 'chat' | 'contacts' | 'workbench',
  filter: '',
  userID: '', nickname: '', faceURL: '', expireAt: 0,
  rtc: { enabled: false, url: '' },
  conversations: [] as any[], activeConv: null as any,
  messages: [] as any[], draft: '', quoteMsg: null as any,
  historyEnd: false, loadingMore: false,
  members: [] as any[],
  progressMap: {} as Record<string, number>,
  typingMap: {} as Record<string, boolean>,
  onlineMap: {} as Record<string, boolean>,
  groups: [] as any[],
  unreadTotal: 0,
  contacts: { depts: [] as mse.ContactDept[], users: [] as mse.ContactUser[], bots: [] as mse.ContactBot[], deptGroupIds: [] as string[] },
  contactsLoading: false, contactsLoaded: false,
  openDepts: [] as string[],
  folded: { bots: false, groups: false },
  showPicker: false, showCardPicker: false,
  drawerVisible: false, drawerGroupId: '', drawerGroupName: '',
  profile: { visible: false, mode: 'user' as 'user' | 'bot' | 'self', userId: '', bot: null as mse.ContactBot | null },
  entries: [] as WbEntry[], activeEntry: null as WbEntry | null, entryUrl: '', entryBusy: false,
  /** 内嵌页（基座 bare 模式）上报的当前子应用路由与标题：分享卡片据此携带页面状态 */
  embedSubPath: '', embedTitle: '',
  showSharePicker: false, shareBusy: false,
  selectMode: false, selectedIds: [] as string[],
  mergePreviewMsg: null as any, viewer: null as { url: string; mode: 'image' | 'video' } | null,
  searchVisible: false,
  settingsOpen: false,
  linkForm: null as null | { title: string; desc: string; url: string; thumbUrl: string; error: string },
})

let sdk: WasmSdk | null = null
let session: ImSession | null = null
/** 通话管理器：登录成功后创建一次（状态机在通话期间持续存活） */
const callMgr = shallowRef<CallManager | null>(null)
/** 工作台跳转基址：由 /im/workbench 下发（租户配置 > 全局 mse.im.web-base-url > 缺省） */
let webBase = 'http://localhost:3000'
let atAllTag = ''
let lastSubscribed = ''
let draftTimer: number | null = null

function setStatus(s: string) { state.status = s }

/* ---------- 主题（深浅双主题，localStorage 持久化） ---------- */

function applyTheme(t: 'light' | 'dark') {
  state.theme = t
  document.documentElement.dataset.theme = t
  try { localStorage.setItem('im-web-theme', t) } catch { /* 忽略 */ }
}
applyTheme(localStorage.getItem('im-web-theme') === 'dark' ? 'dark' : 'light')

function toggleTheme() { applyTheme(state.theme === 'dark' ? 'light' : 'dark') }

/* ---------- 搜索过滤 ---------- */

const filterPlaceholder = computed(() =>
  state.tab === 'chat' ? '搜索会话' : state.tab === 'contacts' ? '搜索联系人 / 群聊' : '搜索应用')

function filterList<T>(list: T[], kw: string, fields: (item: T) => (string | undefined)[]): T[] {
  const k = kw.trim().toLowerCase()
  if (!k) return list
  return list.filter(item => fields(item).some(f => (f || '').toLowerCase().includes(k)))
}

/** 置顶优先，再按最近消息时间倒序 */
const convFiltered = computed(() => {
  const list = filterList(state.conversations, state.filter, c => [c.showName, c.userID, c.groupID])
  return [...list].sort((a, b) => (b.isPinned ? 1 : 0) - (a.isPinned ? 1 : 0) || (b.latestMsgSendTime || 0) - (a.latestMsgSendTime || 0))
})
const groupsFiltered = computed(() => filterList(state.groups, state.filter, g => [g.groupName, g.groupID]))

/** 机器人单聊会话：无头像时兜底绿底 🤖（与消息气泡/资料卡一致） */
function isBotConv(c: any): boolean {
  return !c.groupID && isBotUserId(c.userID)
}

/* ---------- 联系人：组织架构树（部门折叠 + 用户挂部门） ---------- */

interface ContactRow {
  kind: 'dept' | 'user'
  key: string
  id?: string
  name?: string
  level: number
  count?: number
  user?: mse.ContactUser
}

/** 直挂用户按部门分组 */
const usersByDept = computed(() => {
  const m = new Map<string, mse.ContactUser[]>()
  for (const u of state.contacts.users) {
    if (u.deptId == null) continue
    const k = String(u.deptId)
    if (!m.has(k)) m.set(k, [])
    m.get(k)!.push(u)
  }
  return m
})

/** 部门树（父缺失挂根） */
const deptTree = computed(() => {
  const children = new Map<string, { id: string; name: string }[]>()
  const roots: { id: string; name: string }[] = []
  const seen = new Set(state.contacts.depts.map(d => String(d.id)))
  for (const d of state.contacts.depts) {
    const pid = String(d.parentId ?? '0')
    const node = { id: String(d.id), name: d.name }
    if (pid === '0' || !seen.has(pid)) roots.push(node)
    else {
      if (!children.has(pid)) children.set(pid, [])
      children.get(pid)!.push(node)
    }
  }
  return { children, roots }
})

/** 部门人数 = 子树汇总（本部门直挂 + 全部下级部门；path 防脏数据成环死循环） */
const deptTotals = computed(() => {
  const { children, roots } = deptTree.value
  const totals = new Map<string, number>()
  const visit = (id: string, path: Set<string>): number => {
    if (path.has(id)) return 0
    path.add(id)
    let n = (usersByDept.value.get(id) || []).length
    for (const c of children.get(id) || []) n += visit(c.id, path)
    path.delete(id)
    totals.set(id, n)
    return n
  }
  for (const r of roots) visit(r.id, new Set())
  return totals
})

/** 部门树 DFS 扁平行（deptId 空的用户不展示在树上，搜索可命中） */
const contactRows = computed<ContactRow[]>(() => {
  const { children, roots } = deptTree.value
  const totals = deptTotals.value
  const out: ContactRow[] = []
  const walk = (nodes: { id: string; name: string }[], level: number) => {
    for (const n of nodes) {
      out.push({ kind: 'dept', key: 'd' + n.id, id: n.id, name: n.name, level, count: totals.get(n.id) || 0 })
      if (isDeptOpen(n.id)) {
        for (const u of usersByDept.value.get(n.id) || []) out.push({ kind: 'user', key: 'u' + u.userId, level: level + 1, user: u })
        walk(children.get(n.id) || [], level + 1)
      }
    }
  }
  walk(roots, 0)
  return out
})

function isDeptOpen(id: string) { return state.openDepts.includes(id) }

function toggleDept(id: string) {
  const i = state.openDepts.indexOf(id)
  if (i >= 0) state.openDepts.splice(i, 1)
  else state.openDepts.push(id)
}

/** 机器人 / 我的群聊 分组折叠 */
function toggleFold(key: 'bots' | 'groups') { state.folded[key] = !state.folded[key] }

const matchedUsers = computed(() => filterList(state.contacts.users, state.filter, u => [u.nickname]))
const matchedBots = computed(() => filterList(state.contacts.bots, state.filter, b => [b.nickname]))

const deptNames = computed(() => new Map(state.contacts.depts.map(d => [d.id, d.name])))
function deptNameOf(deptId: number | null): string {
  return (deptId != null && deptNames.value.get(deptId)) || ''
}
/** 工作台宫格分组：同名组合并、组内按 sub 再分小节；搜索时过滤后仍按分组呈现（对齐移动端基站） */
const wbGroups = computed(() => {
  const k = state.filter.trim().toLowerCase()
  const groups: { group: string; subs: { sub: string; items: WbEntry[] }[] }[] = []
  const groupMap = new Map<string, { group: string; subs: { sub: string; items: WbEntry[] }[] }>()
  for (const e of state.entries) {
    if (k && !(e.name || '').toLowerCase().includes(k)) continue
    const gname = e.group || '常用'
    let g = groupMap.get(gname)
    if (!g) {
      g = { group: gname, subs: [] }
      groupMap.set(gname, g)
      groups.push(g)
    }
    let s = g.subs.find(x => x.sub === (e.sub || ''))
    if (!s) {
      s = { sub: e.sub || '', items: [] }
      g.subs.push(s)
    }
    s.items.push(e)
  }
  return groups
})

/* ---------- 时间与预览 ---------- */

/** 会话最后一条消息摘要（latestMsg 可能是对象或 JSON 串）+ 草稿优先 */
function convPreview(c: any): string {
  if (c.draftText && c.conversationID === state.activeConv?.conversationID && state.draft) return '[草稿] ' + state.draft
  const m = typeof c.latestMsg === 'string' ? (() => { try { return JSON.parse(c.latestMsg) } catch { return null } })() : c.latestMsg
  if (!m) return ''
  return summaryOf(m)
}

const isTyping = computed(() => !!(state.activeConv && state.typingMap[state.activeConv.conversationID]))
const peerOnline = computed(() => !!(state.activeConv && !state.activeConv.groupID && state.onlineMap[state.activeConv.userID]))

/* ---------- 登录与连接 ---------- */

/** 连接 IM（登录/静默恢复共用）：签发 IM 会话 → SDK 登录 → 状态初始化 → 会话/通讯录预热 */
async function connect() {
  session = await mse.imSession(5)
  state.expireAt = session.expireAt
  state.faceURL = session.faceURL || ''
  state.rtc = session.rtc || { enabled: false, url: '' }
  sdk = createIM()
  bindEvents(sdk)
  await imLogin(sdk, session)
  state.loggedIn = true
  callMgr.value = createCallManager({
    getSdk: () => sdk,
    me: () => ({ userID: state.userID, nickname: state.nickname, faceURL: state.faceURL }),
    rtc: () => state.rtc,
    toast,
  })
  setStatus(`已连接（token 至 ${session.expireAt ? new Date(session.expireAt).toLocaleString() : '未知'}）`)
  await loadConversations()
  // 通讯录预热（非阻塞）：发起群聊/名片/邀请选择器共用，避免首开空态
  ensureContacts()
}

/** 静默恢复：有认证存档（上次登录）即免密重连；凭证失效（401/403）清档回登录页，网络故障保留存档可重试 */
async function tryRestoreLogin() {
  const saved = mse.loadAuth()
  if (!saved) return
  state.busy = true
  try {
    mse.setToken(saved.token)
    const probe = await mse.imSession(5)
    // userID/昵称以会话签发接口为准（不信任存档，人员资料 mse 侧可变）
    state.userID = probe.userID
    state.nickname = probe.nickname
    await connect()
  } catch (e: any) {
    form.tenantCode = saved.tenantCode
    form.username = saved.username
    if (e?.code === 401 || e?.code === 403) {
      mse.clearAuth()
      mse.clearToken()
      setStatus('登录已失效，请重新登录')
    } else {
      setStatus('暂时连不上服务器，登录态已保留，稍后刷新重试')
    }
  } finally {
    state.busy = false
  }
}
tryRestoreLogin()
// 登录页验证码预检（always 模式恒浮出；risk 模式失败计数达标自动浮出；预检失败不阻断登录）
refreshCaptcha()

/** 验证码预检/换图（失败静默：required=false 隐藏验证码行，主链路仍会强制复核） */
async function refreshCaptcha() {
  const data = await mse.captchaNew(form.tenantCode.trim(), form.username.trim())
  captcha.required = data.required
  captcha.captchaId = data.captchaId
  captcha.image = data.image
}

/** 登录成功收口：落认证态 → 连接 IM → 本地存档（两段登录共用） */
async function loginConnected(auth: { tokenValue: string; userId: string; nickname: string }) {
  mse.setToken(auth.tokenValue)
  state.userID = String(auth.userId)
  state.nickname = auth.nickname
  await connect()
  mse.saveAuth({ token: auth.tokenValue, tenantCode: form.tenantCode.trim(), username: form.username.trim() })
}

async function doLogin() {
  if (state.busy) return
  state.busy = true; state.error = ''
  try {
    // 1) mse 账号登录第一段（继承验证码/域名绑定校验）；双因子账号进入第二段
    const resp = await mse.login(form.tenantCode.trim(), form.username.trim(), form.password, {
      captchaId: captcha.captchaId || undefined,
      captchaCode: form.captchaCode || undefined
    })
    if (resp.twoFactorRequired && resp.tempToken) {
      await enterTwoFactor(resp.tempToken, resp.totpEnroll)
      return
    }
    // 2) 直连登录：连接 IM（IM 会话签发 + SDK 登录，签发与管理全在 mse-im 后端）
    await loginConnected(resp.result!)
  } catch (e: any) {
    state.error = e?.message || String(e)
    // 失败即换新验证码并重新预检（risk 模式计数达标自动浮出验证码行）
    form.captchaCode = ''
    refreshCaptcha().catch(() => {})
  } finally {
    state.busy = false
  }
}

/** 进入双因子第二段：拉挑战（enroll 附绑定二维码；否则附邮箱通道可用性） */
async function enterTwoFactor(tempToken: string, enroll: boolean) {
  twoFactor.active = true
  twoFactor.tempToken = tempToken
  twoFactor.enroll = enroll
  twoFactor.code = ''
  twoFactor.emailChannel = false
  twoFactor.emailAvailable = false
  twoFactor.maskedEmail = ''
  twoFactor.qrImage = ''
  twoFactor.secret = ''
  try {
    const c = await mse.twoFactorChallenge(tempToken)
    twoFactor.qrImage = c.enrollment?.qrImage || ''
    twoFactor.secret = c.enrollment?.secret || ''
    twoFactor.emailAvailable = c.emailAvailable
    twoFactor.maskedEmail = c.maskedEmail || ''
    state.error = ''
  } catch (e: any) {
    twoFactor.active = false
    state.error = e?.message || '获取双因子信息失败'
  }
}

/** 改用/重发邮箱动态码（60s 冷却；发送后本步切换到邮箱通道语义） */
async function sendEmailCode() {
  if (twoFactor.sending || twoFactor.countdown > 0) return
  twoFactor.sending = true
  try {
    await mse.sendTwoFactorEmailCode(twoFactor.tempToken)
    twoFactor.emailChannel = true
    twoFactor.code = ''
    twoFactor.countdown = 60
    if (emailTimer) clearInterval(emailTimer)
    emailTimer = setInterval(() => {
      twoFactor.countdown -= 1
      if (twoFactor.countdown <= 0 && emailTimer) {
        clearInterval(emailTimer)
        emailTimer = null
      }
    }, 1000)
  } catch (e: any) {
    state.error = e?.message || '发送邮箱验证码失败'
  } finally {
    twoFactor.sending = false
  }
}

/** 双因子第二段提交：TOTP（或绑定确认）/ 邮箱验证码换正式登录态后连接 IM */
async function doSecondFactor() {
  if (state.busy) return
  if (!twoFactor.code.trim()) {
    state.error = '请输入动态验证码'
    return
  }
  state.busy = true; state.error = ''
  try {
    const result = await mse.twoFactorLogin(twoFactor.tempToken, twoFactor.code.trim(), twoFactor.emailChannel ? 'email' : 'totp')
    await loginConnected(result)
  } catch (e: any) {
    // 验证失败保留输入便于重试；超限后端作废临时凭证，返回重填账号重新登录
    state.error = e?.message || String(e)
  } finally {
    state.busy = false
  }
}

/** 返回第一段重填账号（临时凭证留在服务端自然过期） */
function backToForm() {
  twoFactor.active = false
  twoFactor.code = ''
  state.error = ''
}

function bindEvents(s: WasmSdk) {
  s.on(CbEvents.OnConnectSuccess, () => { setStatus('已连接'); state.connected = true })
  s.on(CbEvents.OnConnecting, () => { setStatus('连接中…'); state.connected = false })
  s.on(CbEvents.OnConnectFailed, () => { setStatus('连接失败'); state.connected = false })
  // 新消息实时入口（wasm SDK 无 OnNewMessage 事件）：在线推送 OnRecvNewMessage(s)
  // 与登录离线同步 OnRecvOfflineNewMessage(s) 归一消费，数组/单条两种 payload 兼容，
  // 单条+数组双事件重复投递由 pushMessage 按 clientMsgID 去重；离线同步不触发系统通知
  const onRecvMessages = (data: any, offline = false) => {
    const d = data?.data ?? data
    for (const msg of (Array.isArray(d) ? d : [d])) {
      if (!msg) continue
      const sig = parseCustom(msg.customElem?.data)
      if (isCallSignal(sig)) { callMgr.value?.handleSignal(sig); continue }
      pushMessage(msg)
      if (!offline) desktopNotify(msg)
    }
  }
  s.on(CbEvents.OnRecvNewMessages, (d: any) => onRecvMessages(d))
  s.on(CbEvents.OnRecvNewMessage, (d: any) => onRecvMessages(d))
  s.on(CbEvents.OnRecvOfflineNewMessages, (d: any) => onRecvMessages(d, true))
  s.on(CbEvents.OnRecvOfflineNewMessage, (d: any) => onRecvMessages(d, true))
  // 仅在线消息（isOnlineOnly：通话信令等）——不落库不进会话
  s.on(CbEvents.OnRecvOnlineOnlyMessage, (data: any) => {
    const msg = data?.data ?? data
    const v = parseCustom(msg?.customElem?.data)
    if (isCallSignal(v)) callMgr.value?.handleSignal(v)
  })
  // 媒体上传进度（clientMsgID 定位气泡）
  s.on(CbEvents.OnProgress, (data: any) => {
    const p = data?.data ?? data
    if (p?.clientMsgID && typeof p.progress === 'number') state.progressMap[p.clientMsgID] = p.progress
  })
  // 撤回通知：本地列表即时置为撤回行
  s.on(CbEvents.OnNewRecvMessageRevoked, (data: any) => {
    const info = data?.data ?? data
    if (!info?.clientMsgID) return
    const idx = state.messages.findIndex(m => m.clientMsgID === info.clientMsgID)
    if (idx >= 0) {
      state.messages[idx] = {
        ...state.messages[idx],
        contentType: 2101,
        content: JSON.stringify({
          revokerID: info.revokerID, revokerNickname: info.revokerNickname,
          sourceMessageSenderNickname: info.sourceMessageSenderNickname,
        }),
      }
    }
    loadConversations()
  })
  // C2C 已读回执：对端读了我发的消息（msgIDList 为服务端消息 id）
  s.on(CbEvents.OnRecvC2CReadReceipt, (data: any) => {
    const list = (data?.data ?? data) as any[]
    if (!Array.isArray(list)) return
    const ids = new Set(list.flatMap(r => r.msgIDList || []))
    for (const m of state.messages) {
      if (m.sendID === state.userID && (ids.has(m.serverMsgID) || ids.has(m.clientMsgID))) m.isRead = true
    }
  })
  // 正在输入
  s.on(CbEvents.OnConversationUserInputStatusChanged, (data: any) => {
    const p = data?.data ?? data
    if (!p?.conversationID) return
    state.typingMap[p.conversationID] = Array.isArray(p.platformIDs) ? p.platformIDs.length > 0 : !!p.platformIDs
    if (state.typingMap[p.conversationID]) {
      setTimeout(() => { state.typingMap[p.conversationID] = false }, 6000)
    }
  })
  // 在线状态
  s.on(CbEvents.OnUserStatusChanged, (data: any) => {
    const p = data?.data ?? data
    if (p?.userID) state.onlineMap[p.userID] = p.status === 1
  })
  // 会话列表变化（新会话/未读数等）实时刷新
  s.on(CbEvents.OnConversationChanged, () => loadConversations())
  s.on(CbEvents.OnNewConversation, () => loadConversations())
  s.on(CbEvents.OnTotalUnreadMessageCountChanged, (data: any) => {
    const v = data?.data ?? data
    state.unreadTotal = typeof v === 'number' ? v : Number(v) || 0
  })
  // token 过期/失效：自动重签并重连（mse-im 会话接口按当前 mse 会话重新签发）
  const resign = async () => {
    if (!sdk || !session) return
    setStatus('IM token 已过期，自动重签…')
    try {
      session = await mse.imSession(5)
      state.expireAt = session.expireAt
      state.rtc = session.rtc || state.rtc
      await imLogin(sdk, session)
      setStatus('已重连')
    } catch (e: any) {
      // 凭证失效（401/403）= mse 会话已失效（30 天过期/被下线），清档回登录页；网络故障保留登录态等待重试
      if (e?.code === 401 || e?.code === 403) {
        await logout()
        setStatus('登录已失效，请重新登录')
      } else {
        setStatus('重签暂时失败（网络异常），将自动重试')
      }
    }
  }
  s.on(CbEvents.OnUserTokenExpired, resign)
  s.on(CbEvents.OnUserTokenInvalid, resign)
  s.on(CbEvents.OnUserTokenWillExpire, resign)
}

/** SDK 返回值兼容：部分方法直接返回数组/对象，部分包 { data } */
function unwrapArr(r: any): any[] { return Array.isArray(r) ? r : (r?.data ?? []) }

async function loadConversations() {
  if (!sdk) return
  try { state.conversations = unwrapArr(await sdk.getAllConversationList()) } catch { /* 保持现值 */ }
  refreshUnread()
}

async function refreshUnread() {
  if (!sdk) return
  try {
    const r: any = await (sdk as any).getTotalUnreadMsgCount()
    state.unreadTotal = typeof r === 'number' ? r : Number(r?.data ?? 0) || 0
  } catch { /* 忽略 */ }
}

/** 打开会话即标记已读（否则列表未读角标不清零）。activeConv 往往是 loadConversations 整组换数组前的旧对象，
 *  其 unreadCount 已过期，直接拿它判空会误跳过（停在会话里收新消息角标永远不清零）——以列表内最新会话为准 */
async function markRead(c: any) {
  if (!sdk || !c?.conversationID) return
  const cur = state.conversations.find(x => x.conversationID === c.conversationID) ?? c
  if (!cur.unreadCount) return
  try {
    await sdk.markConversationMessageAsRead(cur.conversationID)
    cur.unreadCount = 0
    if (cur !== c) c.unreadCount = 0
  } catch { /* 忽略 */ }
}

async function openConv(c: any) {
  if (state.selectMode) exitSelect()
  saveDraftOf(state.activeConv)
  state.activeConv = c
  state.quoteMsg = null
  state.draft = c.draftText || ''
  state.historyEnd = false
  loadConvMembers(c)
  await loadHistory()
  markRead(c)
  subscribePresence(c)
}

async function openWith(sourceID: string, sessionType: 1 | 3) {
  if (!sdk) return
  try {
    const conv: any = await sdk.getOneConversation({ sourceID, sessionType })
    state.activeConv = conv?.data ?? conv
    state.tab = 'chat'
    state.draft = state.activeConv.draftText || ''
    state.historyEnd = false
    loadConvMembers(state.activeConv)
    await loadHistory()
    markRead(state.activeConv)
    subscribePresence(state.activeConv)
  } catch (e: any) {
    setStatus('打开会话失败：' + (e?.message || e))
    toast('打开会话失败：' + (e?.message || e), 'error')
  }
}

/** 点联系人/机器人：先按需注册（未登录过 IM 的用户），再开单聊 */
async function openUser(u: mse.ContactUser) {
  try { await mse.ensureUsers([u.userId]) } catch { /* 注册失败仍尝试打开（可能已注册） */ }
  await openWith(String(u.userId), 1)
}

function openBot(b: mse.ContactBot) { openWith(b.botUserId, 1) }

/* ---------- 资料卡（用户/机器人/本人三态；入口：头像点击、群成员行、消息头像、rail 自己） ---------- */

async function openProfile(userId: string) {
  state.profile.mode = 'user'
  state.profile.userId = userId
  state.profile.bot = null
  state.profile.visible = true
}

function openBotProfile(b: mse.ContactBot) {
  state.profile.mode = 'bot'
  state.profile.userId = b.botUserId
  state.profile.bot = b
  state.profile.visible = true
}

function openProfileSelf() {
  state.profile.mode = 'self'
  state.profile.userId = state.userID
  state.profile.bot = null
  state.profile.visible = true
}

/** 消息发送者头像点击：bot_ 前缀走机器人资料（从通讯录集取），自己走本人模式，其余用户资料 */
function onSenderProfile(senderId: string) {
  if (senderId === state.userID) return openProfileSelf()
  const bot = state.contacts.bots.find(b => b.botUserId === senderId)
  if (bot) return openBotProfile(bot)
  openProfile(senderId)
}

/** 群成员行点击：同消息头像分流 */
function onDrawerMember(m: any) {
  onSenderProfile(String(m.userID || ''))
}

/** 资料卡「发消息」：先按需注册（用户），再开单聊 */
async function profileChat() {
  const p = state.profile
  p.visible = false
  if (p.mode === 'bot' && p.bot) return openBot(p.bot)
  if (p.mode === 'user' && p.userId) {
    try { await mse.ensureUsers([p.userId]) } catch { /* 注册失败仍尝试打开（可能已注册） */ }
    await openWith(p.userId, 1)
  }
}

/** 自设头像上传/恢复后：更新本地回显（后续 session 签发即带新值；消息气泡头像由 OpenIM 推送同步） */
function onAvatarChanged(faceURL: string) {
  state.faceURL = faceURL
}

/** 点「我的群聊」：打开群会话 */
function openGroup(g: any) { openWith(g.groupID, 3) }

/** 建群（群主=当前用户；成员/机器人混选）成功后直接打开群会话 */
async function doCreateGroup(payload: { name: string; userIds: number[]; botUserIds: string[] }) {
  try {
    const g = await mse.createGroup({
      name: payload.name || undefined,
      memberUserIds: payload.userIds,
      botUserIds: payload.botUserIds,
    })
    state.showPicker = false
    setStatus(`已创建群聊「${g.groupName}」`)
    toast(`已创建群聊「${g.groupName}」`, 'success')
    await loadContacts()
    await openWith(g.groupId, 3)
  } catch (e: any) {
    setStatus('建群失败：' + (e?.message || e))
    toast('建群失败：' + (e?.message || e), 'error')
  }
}

/** 会话头名称点击：群开群资料抽屉，单聊开对方资料卡 */
function onConvHeadClick() {
  const c = state.activeConv
  if (!c) return
  if (c.groupID) openGroupDrawer()
  else onSenderProfile(String(c.userID || ''))
}

/** 群会话右上「…」：群资料抽屉（成员/邀请/管理） */
function openGroupDrawer() {
  const c = state.activeConv
  if (!c?.groupID) return
  state.drawerGroupId = c.groupID
  state.drawerGroupName = c.showName || c.groupID || ''
  state.drawerVisible = true
  // 邀请弹窗依赖通讯录（可能从未进过「联系人」页签）：打开抽屉即兜底加载
  ensureContacts()
}

/** 选择器打开即兜底加载通讯录（登录预热通常已就绪，这里只防漏） */
function openGroupPicker() {
  state.showPicker = true
  ensureContacts()
}

function openCardPicker() {
  state.showCardPicker = true
  ensureContacts()
}

function onGroupRenamed() { loadConversations() }

/** 群图标变更：会话列表与「我的群聊」缓存都按新 faceURL 重取 */
function onGroupAvatarChanged() {
  loadConversations()
  loadContacts()
}

/** 群成员加载（@ 候选与群角色判定来源）；单聊清空 */
function loadConvMembers(c: any) {
  const gid = c?.groupID
  if (!gid) { state.members = []; return }
  loadMembers(gid)
}

async function loadMembers(groupId: string) {
  if (!sdk || !groupId) return
  try {
    // SDK 形态兼容：对象参数或位置参数
    let res: any
    try {
      res = await (sdk as any).getGroupMemberList({ groupID: groupId, filter: 0, offset: 0, count: 500 })
    } catch {
      res = await (sdk as any).getGroupMemberList(groupId, 0, 500, 0)
    }
    state.members = Array.isArray(res) ? res : (res?.data ?? [])
  } catch { state.members = [] }
}

/** 群抽屉成员/管理动作后：群列表与当前会话成员（@ 候选）一并刷新 */
function onGroupChanged() {
  loadContacts()
  if (state.drawerGroupId && state.activeConv?.groupID === state.drawerGroupId) loadMembers(state.drawerGroupId)
}

/* ---------- 在线状态订阅（仅单聊对端） ---------- */

async function subscribePresence(c: any) {
  if (!sdk) return
  if (lastSubscribed) {
    try { await sdk.unsubscribeUsersStatus([lastSubscribed]) } catch { /* 忽略 */ }
    lastSubscribed = ''
  }
  if (!c || c.groupID || !c.userID) return
  try {
    const r: any = await sdk.getUserStatus([c.userID])
    const list = Array.isArray(r) ? r : (r?.data ?? [])
    const st = list.find((s: any) => s.userID === c.userID)
    state.onlineMap[c.userID] = st ? st.status === 1 : false
    await sdk.subscribeUsersStatus([c.userID])
    lastSubscribed = c.userID
  } catch { /* 服务端不支持等场景静默 */ }
}

/* ---------- 历史与分页 ---------- */

async function loadHistory() {
  if (!sdk || !state.activeConv) return
  try {
    const res: any = await sdk.getAdvancedHistoryMessageList({
      conversationID: state.activeConv.conversationID, count: 20, startClientMsgID: '', viewType: 0,
    })
    const d = res?.data ?? res
    state.messages = d?.messageList ?? []
    state.historyEnd = !!d?.isEnd
    await msgList.value?.scrollToBottom?.()
  } catch { state.messages = [] }
}

async function loadMore() {
  if (!sdk || !state.activeConv || state.loadingMore || state.historyEnd || !state.messages.length) return
  state.loadingMore = true
  try {
    const res: any = await sdk.getAdvancedHistoryMessageList({
      conversationID: state.activeConv.conversationID,
      startClientMsgID: state.messages[0].clientMsgID,
      count: 20,
      viewType: 0,
    })
    const d = res?.data ?? res
    const older = d?.messageList ?? []
    state.historyEnd = !!d?.isEnd
    if (older.length) state.messages = [...older, ...state.messages]
  } catch { /* 忽略 */ } finally {
    state.loadingMore = false
  }
}

/** 推送 MessageItem 不带 conversationID（SDK 类型即无此字段，历史拉取的消息才有）：
 *  按 OpenIM 规则现算——群聊 sg_+groupID，单聊 si_+双 id 字典序（与 getAllConversationList 一致） */
function convIdOf(msg: any): string {
  if (msg.conversationID) return msg.conversationID
  if (msg.sessionType === 3 || (!msg.recvID && msg.groupID)) return 'sg_' + (msg.groupID || '')
  return 'si_' + [msg.sendID, msg.recvID].filter(Boolean).sort().join('_')
}

function pushMessage(msg: any) {
  if (!msg) return
  // 通话信令（在线消息）不进会话列表
  const sig = parseCustom(msg.customElem?.data)
  if (isCallSignal(sig)) { callMgr.value?.handleSignal(sig); return }
  if (state.activeConv && convIdOf(msg) === state.activeConv.conversationID) {
    // 去重（本地发送已 push 占位，服务器回执事件可能再推一次）
    const idx = state.messages.findIndex(m => m.clientMsgID === msg.clientMsgID)
    if (idx >= 0) state.messages[idx] = msg
    else {
      state.messages.push(msg)
      msgList.value?.scrollToBottom?.()
    }
    if (msg.sendID !== state.userID) markRead(state.activeConv)
  }
  loadConversations()
}

/* ---------- 桌面端（Electron 壳）协作：新消息系统通知 + 未读角标上报 ---------- */

/** 消息通知开关（设置页；localStorage 持久化，缺省开） */
const notifyEnabled = ref(localStorage.getItem('im-web-notify') !== '0')
watch(notifyEnabled, v => {
  try { localStorage.setItem('im-web-notify', v ? '1' : '0') } catch { /* 忽略 */ }
})

/** 满屏设置页：Esc 关闭（与右上 ✕ 同效） */
function onGlobalKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && state.settingsOpen) state.settingsOpen = false
}
window.addEventListener('keydown', onGlobalKeydown)
onBeforeUnmount(() => window.removeEventListener('keydown', onGlobalKeydown))

/** 无头像图时的字头像（与会话列表 .face.char 同款：蓝底白字首字符、机器人绿底🤖），
 *  canvas 画成 PNG dataURL 交主进程当通知大图（主进程无 DOM，字渲染只能在页面做） */
function initialAvatarDataUrl(name: string, bot: boolean): string {
  try {
    const s = 128
    const canvas = document.createElement('canvas')
    canvas.width = s
    canvas.height = s
    const ctx = canvas.getContext('2d')
    if (!ctx) return ''
    ctx.fillStyle = bot ? '#00b04f' : (imPrimary() || '#3370ff')
    ctx.fillRect(0, 0, s, s)
    ctx.fillStyle = '#fff'
    ctx.font = '600 60px system-ui, -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(bot ? '🤖' : (name || '?').charAt(0), s / 2, s / 2 + 4)
    return canvas.toDataURL('image/png')
  } catch { return '' }
}

/** 新消息系统通知（主进程出原生 toast；浏览器版无桥自动不生效）。
 *  离线同步不提醒；当前会话在前台不打扰；免打扰会话跳过；tag=会话 id 同会话覆盖合并。
 *  左侧大图用会话头像：群→群头像（faceURL），单聊→对方头像；无头像图时画同款字头像，
 *  两者皆缺主进程兜底应用 logo。通知样式（标题行应用名+图标、头像拉取）由桌面壳统一负责。 */
function desktopNotify(msg: any) {
  if (!hasDesktop || !notifyEnabled.value) return
  if (!msg || msg.sendID === state.userID) return
  const cid = convIdOf(msg)
  const conv = state.conversations.find((c: any) => c.conversationID === cid)
  if (conv?.recvMsgOpt === 2) return
  if (state.activeConv?.conversationID === cid && !document.hidden) return
  const title = conv?.showName || msg.senderNickname || msg.sendID || '新消息'
  const body = summaryOf(msg) || '[新消息]'
  const payload: any = { title, body, tag: cid, convId: cid }
  const avatar = assetUrl(conv?.faceURL || msg.senderFaceUrl || '')
  if (/^https?:/i.test(avatar)) payload.avatar = avatar
  else payload.avatarData = initialAvatarDataUrl(title, isBotConv(conv || {}))
  notifyDesktop(payload)
}

/** 未读数上报：窗口隐藏时桌面壳展示托盘 tooltip + 任务栏叠标 + 闪烁，聚焦/显示即清除；
 *  通知点击直达会话（主进程已唤回并聚焦主窗口，这里只负责切到对应会话） */
if (hasDesktop) {
  const pushBadge = () => reportBadge(document.hidden ? state.unreadTotal : 0)
  watch(() => state.unreadTotal, pushBadge)
  document.addEventListener('visibilitychange', pushBadge)
  onNotifyClick((convId: string) => {
    state.tab = 'chat'
    const target = state.conversations.find((c: any) => c.conversationID === convId)
    if (target) openConv(target)
  })
}

/* ---------- 发送（文本/@/引用/媒体/名片/链接卡片） ---------- */

/** 发送占位 → 替换结果；失败保留 status=3 供重发 */
async function sendCreated(created: any) {
  if (!sdk || !state.activeConv) return
  const c = state.activeConv
  const message = created?.data ?? created
  if (!message) return
  message.status = 1
  state.messages.push(message)
  msgList.value?.scrollToBottom?.()
  try {
    const sent: any = await sdk.sendMessage({ recvID: c.recvID ?? c.userID ?? '', groupID: c.groupID ?? '', message })
    const idx = state.messages.findIndex(m => m.clientMsgID === message.clientMsgID)
    if (idx >= 0) state.messages[idx] = sent.data ?? sent
    delete state.progressMap[message.clientMsgID]
  } catch {
    message.status = 3
    delete state.progressMap[message.clientMsgID]
  }
  loadConversations()
}

async function sendText(p: { text: string; atUserList: string[]; atUsersInfo: any[]; atAll: boolean; quote: any | null }) {
  if (!sdk || !state.activeConv || !p.text.trim()) return
  let created: any
  if (p.quote) {
    created = await sdk.createQuoteMessage({ text: p.text, message: JSON.stringify(p.quote) })
  } else if (p.atUserList.length || p.atAll) {
    // @ 全员：text 中的「@所有人」替换为服务端识别的 at-all 标记（标记串大小写随服务端版本有差，展示端 atTextDisplay 大小写不敏感还原）
    if (!atAllTag) { try { atAllTag = ((await (sdk as any).getAtAllTag()) as any)?.data || 'atAllTag' } catch { atAllTag = 'atAllTag' } }
    const ids = [...p.atUserList]
    if (p.atAll) ids.push(atAllTag)
    const text = p.atAll ? p.text.replace(/@所有人/g, atAllTag) : p.text
    created = await sdk.createTextAtMessage({
      text,
      atUserIDList: ids.length ? ids : [atAllTag],
      atUsersInfo: p.atUsersInfo,
    })
  } else {
    created = await sdk.createTextMessage(p.text)
  }
  await sendCreated(created)
}

async function sendImage(p: any) {
  if (!sdk) return
  await sendCreated(await buildImageMessage(sdk, p))
}

async function sendFile(file: File) {
  if (!sdk) return
  await sendCreated(await buildFileMessage(sdk, file))
}

async function sendSound(p: { file: File; duration: number }) {
  if (!sdk) return
  await sendCreated(await buildSoundMessage(sdk, p))
}

async function sendVideo(p: any) {
  if (!sdk) return
  await sendCreated(await buildVideoMessage(sdk, p))
}

/** 名片发送（单选确认回调：payload 含 userIds/botUserIds，取第一项） */
async function sendCard(payload: { userIds: number[]; botUserIds: string[] }) {
  state.showCardPicker = false
  if (!sdk) return
  let userID = '', nickname = '', faceURL = ''
  if (payload.userIds.length) {
    const u = state.contacts.users.find(x => x.userId === payload.userIds[0])
    userID = String(payload.userIds[0]); nickname = u?.nickname || ''; faceURL = u?.avatar || ''
  } else if (payload.botUserIds.length) {
    const b = state.contacts.bots.find(x => x.botUserId === payload.botUserIds[0])
    userID = payload.botUserIds[0]; nickname = b?.nickname || ''; faceURL = b?.faceUrl || ''
  }
  if (!userID) return
  await sendCreated(await sdk.createCardMessage({ userID, nickname, faceURL, ex: '' }))
}

function openLinkForm() {
  state.linkForm = { title: '', desc: '', url: '', thumbUrl: '', error: '' }
}

async function sendLink() {
  const f = state.linkForm
  if (!f || !sdk) return
  if (!f.title.trim() || !f.url.trim()) { f.error = '标题与链接必填'; return }
  if (!/^https?:\/\//i.test(f.url.trim())) { f.error = '链接需以 http(s):// 开头'; return }
  const card = { customType: 'link-card', title: f.title.trim(), desc: f.desc.trim(), url: f.url.trim(), thumbUrl: f.thumbUrl.trim() }
  state.linkForm = null
  const created = await sdk.createCustomMessage({ data: JSON.stringify(card), extension: '', description: card.title })
  await sendCreated(created)
}

/* ---------- 消息操作（右键菜单） ---------- */

const msgMenu = ref<{ x: number; y: number; msg: any } | null>(null)
const convMenu = ref<{ x: number; y: number; conv: any } | null>(null)

function openMsgMenu(p: { x: number; y: number; msg: any }) {
  msgMenu.value = p
}

const myRole = computed(() => state.members.find(m => m.userID === state.userID)?.roleLevel || 0)

const msgMenuItems = computed<CtxItem[]>(() => {
  const m = msgMenu.value?.msg
  if (!m) return []
  const textLike = [101, 106, 110, 114].includes(m.contentType)
  const canRevoke = m.sendID === state.userID && Date.now() - (m.sendTime || 0) < 120_000
    || (!!state.activeConv?.groupID && (myRole.value === 100 || myRole.value === 60))
  return [
    { key: 'copy', label: '复制', disabled: !textLike || !summaryOf(m) },
    { key: 'quote', label: '引用' },
    { key: 'forward', label: '转发' },
    { key: 'multi', label: '多选' },
    { key: 'revoke', label: '撤回', disabled: !canRevoke, danger: true },
    { key: 'delete', label: '删除', danger: true },
    ...(m.status === 3 && m.sendID === state.userID ? [{ key: 'resend', label: '重发' } as CtxItem] : []),
  ]
})

async function onMsgMenu(key: string) {
  const m = msgMenu.value?.msg
  if (!m || !sdk) return
  switch (key) {
    case 'copy':
      await copyText(m.textElem?.content || m.atTextElem?.text || m.customElem?.data || m.quoteElem?.text || '')
      toast('已复制', 'success')
      break
    case 'quote':
      state.quoteMsg = m
      break
    case 'forward':
      forwardState.value = { msgs: [m], merge: false }
      break
    case 'multi':
      state.selectMode = true
      state.selectedIds = []
      break
    case 'revoke':
      await revokeMsg(m)
      break
    case 'delete':
      try {
        await sdk.deleteMessage({ conversationID: m.conversationID, clientMsgID: m.clientMsgID })
        const i = state.messages.findIndex(x => x.clientMsgID === m.clientMsgID)
        if (i >= 0) state.messages.splice(i, 1)
      } catch { /* 忽略 */ }
      break
    case 'resend':
      await resend(m)
      break
  }
}

async function revokeMsg(m: any) {
  if (!sdk) return
  try {
    await sdk.revokeMessage({ conversationID: m.conversationID, clientMsgID: m.clientMsgID })
    const i = state.messages.findIndex(x => x.clientMsgID === m.clientMsgID)
    if (i >= 0) {
      state.messages[i] = {
        ...state.messages[i],
        contentType: 2101,
        content: JSON.stringify({ revokerID: state.userID, revokerNickname: state.nickname, sourceMessageSenderNickname: state.nickname }),
      }
    }
  } catch (e: any) {
    setStatus('撤回失败：' + (e?.errMsg || e?.message || e))
    toast('撤回失败：' + (e?.errMsg || e?.message || e), 'error')
  }
}

async function resend(m: any) {
  if (!sdk || !state.activeConv) return
  const c = state.activeConv
  try {
    m.status = 1
    const sent: any = await sdk.sendMessage({ recvID: c.recvID ?? c.userID ?? '', groupID: c.groupID ?? '', message: m })
    const idx = state.messages.findIndex(x => x.clientMsgID === m.clientMsgID)
    if (idx >= 0) state.messages[idx] = sent.data ?? sent
  } catch {
    m.status = 3
  }
}

function toggleSelect(m: any) {
  const i = state.selectedIds.indexOf(m.clientMsgID)
  if (i >= 0) state.selectedIds.splice(i, 1)
  else state.selectedIds.push(m.clientMsgID)
}

function exitSelect() {
  state.selectMode = false
  state.selectedIds = []
}

/* ---------- 转发 ---------- */

const forwardState = ref<{ msgs: any[]; merge: boolean } | null>(null)
const forwardBusy = ref(false)

async function forwardSelected(merge: boolean) {
  const msgs = state.selectedIds
    .map(id => state.messages.find(m => m.clientMsgID === id))
    .filter(Boolean)
  if (!msgs.length) return
  forwardState.value = { msgs, merge }
}

async function doForward(targets: { sourceID: string; sessionType: 1 | 3; name: string }[]) {
  if (!sdk || !forwardState.value || !targets.length) return
  forwardBusy.value = true
  try {
    const { msgs, merge } = forwardState.value
    // 合并转发消息体对所有目标复用同一条
    const merged = merge && msgs.length >= 2
      ? (await sdk.createMergerMessage({
          messageList: msgs,
          title: `[聊天记录] 共${msgs.length}条`,
          summaryList: msgs.map(m => `${m.senderNickname || m.sendID}：${summaryOf(m)}`).slice(0, 3),
        }))?.data
      : null
    for (const target of targets) {
      if (merged) {
        await sdk.sendMessage({ recvID: target.sessionType === 1 ? target.sourceID : '', groupID: target.sessionType === 3 ? target.sourceID : '', message: merged })
      } else {
        // 逐条转发
        for (const m of msgs) {
          const created = await sdk.createForwardMessage(m)
          await sdk.sendMessage({ recvID: target.sessionType === 1 ? target.sourceID : '', groupID: target.sessionType === 3 ? target.sourceID : '', message: created?.data ?? created })
        }
      }
      setStatus(`已转发给 ${target.name}`)
      toast(`已转发给 ${target.name}`, 'success')
    }
    forwardState.value = null
  } catch (e: any) {
    setStatus('转发失败：' + (e?.message || e))
    toast('转发失败：' + (e?.message || e), 'error')
  } finally {
    forwardBusy.value = false
  }
}

/* ---------- 消息定位（引用/搜索结果） ---------- */

function locateMsg(m: any) {
  // 被引用消息在本会话内：滚动定位；否则提示
  const target = state.messages.find(x => x.clientMsgID === m?.quoteElem?.quoteMessage?.clientMsgID)
  if (target) msgList.value?.scrollToMsg?.(target.clientMsgID)
  else toast('原消息不在当前加载范围')
}

function locateSearchHit(p: { conversationID: string; msg: any }) {
  state.searchVisible = false
  const conv = state.conversations.find(c => c.conversationID === p.conversationID)
  if (conv) {
    openConv(conv).then(() => {
      setTimeout(() => msgList.value?.scrollToMsg?.(p.msg?.clientMsgID), 300)
    })
  } else if (sdk) {
    // 本地无该会话（历史会话）：拉一次会话再打开
    sdk.getMultipleConversation([p.conversationID]).then((r: any) => {
      const c = unwrapArr(r)[0]
      if (c) openConv(c)
    }).catch(() => toast('会话不存在', 'error'))
  }
}

function openViewer(p: { url: string; mode: 'image' | 'video' }) {
  if (!p.url) { toast('素材地址为空', 'error'); return }
  state.viewer = p
}

/** 点名片气泡：直接与对方开会话 */
function openCard(m: any) {
  const card = m?.cardElem
  if (!card?.userID) return
  openWith(card.userID, 1)
}

/* ---------- 会话右键菜单 ---------- */

function openConvMenu(e: MouseEvent, c: any) {
  convMenu.value = { x: e.clientX, y: e.clientY, conv: c }
}

const convMenuItems = computed<CtxItem[]>(() => {
  const c = convMenu.value?.conv
  if (!c) return []
  return [
    { key: 'pin', label: c.isPinned ? '取消置顶' : '置顶' },
    { key: 'mute', label: c.recvMsgOpt === 2 ? '开启消息提醒' : '消息免打扰' },
    { key: 'read', label: '标记已读', disabled: !c.unreadCount },
    { key: 'delete', label: '删除会话', danger: true },
  ]
})

async function onConvMenu(key: string) {
  const c = convMenu.value?.conv
  if (!c || !sdk) return
  switch (key) {
    case 'pin':
      try { await sdk.setConversation({ conversationID: c.conversationID, isPinned: !c.isPinned }); c.isPinned = !c.isPinned } catch { /* 忽略 */ }
      break
    case 'mute':
      try { await sdk.setConversation({ conversationID: c.conversationID, recvMsgOpt: c.recvMsgOpt === 2 ? 0 : 2 }); c.recvMsgOpt = c.recvMsgOpt === 2 ? 0 : 2 } catch { /* 忽略 */ }
      break
    case 'read':
      await markRead(c)
      break
    case 'delete':
      try {
        await sdk.deleteConversationAndDeleteAllMsg(c.conversationID)
        await loadConversations()
        if (state.activeConv?.conversationID === c.conversationID) state.activeConv = null
      } catch { /* 忽略 */ }
      break
  }
}

/* ---------- 草稿 ---------- */

function onDraft(v: string) {
  state.draft = v
  if (draftTimer) clearTimeout(draftTimer)
  draftTimer = window.setTimeout(() => saveDraftOf(state.activeConv, state.draft), 1200)
}

function saveDraftOf(c: any, text?: string) {
  if (!sdk || !c?.conversationID) return
  const draft = text ?? (c.conversationID === state.activeConv?.conversationID ? state.draft : c.draftText || '')
  try { sdk.setConversationDraft({ conversationID: c.conversationID, draftText: draft }) } catch { /* 忽略 */ }
}

/* ---------- 正在输入上报 ---------- */

function reportTyping(focus: boolean) {
  if (!sdk || !state.activeConv) return
  try { sdk.changeInputStates({ conversationID: state.activeConv.conversationID, focus }) } catch { /* 忽略 */ }
}

/** 光标落进输入框 = 正盯着该会话（微信式「正在看即已读」）：上报输入聚焦状态，同时清掉该会话未读角标 */
function onTypingFocus() {
  reportTyping(true)
  markRead(state.activeConv)
}

/* ---------- 通话 ---------- */

function startCall(media: 'audio' | 'video') {
  const c = state.activeConv
  if (!c || c.groupID || !callMgr.value) return
  callMgr.value.startCall({ userID: c.userID, nickname: c.showName || c.userID, faceURL: c.faceURL || '' }, media)
}

/* ---------- 工作台（左宫格 + 右侧内嵌/弹窗） ---------- */

/** 工作台入口（entryType 0=mse 菜单路由 1=外部应用 URL；openMode 0=内嵌 1=弹窗；authMode 0=直连 1=免登授权） */
interface WbEntry {
  id?: string | number
  entryType: number
  path: string
  name: string
  icon: string
  openMode: number
  authMode: number
  /** 分组标题：顶层目录名（无目录归「常用」；外部应用归「外部应用」） */
  group?: string
  /** 子组标题：二级目录名 */
  sub?: string
  /** 图标配色种子：菜单 id 等稳定值，决定取哪一色 */
  seed?: number
  opening?: boolean
}

const DEFAULT_ICON = '🧩'
/** 页面菜单入口无图标时的最终兜底：Element Plus Box 图标，与基座侧边栏对 type=3 页面菜单的缺省同口径 */
const MENU_DEFAULT_ICON = 'Box'

/** 图标渲染：http(s) 绝对地址或站内相对路径（/fs/…、/im/workbench/asset/… 本地兜底）为图片，否则按 emoji/字符展示 */
function isImgIcon(icon: string) { return /^https?:\/\//i.test(icon) || icon.startsWith('/') }

/** 入口有效图标：配置 icon → 菜单 icon（Element Plus 图标名/emoji/图片地址）→ 菜单入口 Box / 外部应用空（链条 SVG） */
function effectiveIcon(icon: string | undefined, menuIcon: string | undefined, isMenu: boolean) {
  return icon || menuIcon || (isMenu ? MENU_DEFAULT_ICON : '')
}

/** 应用图标色板：与移动端基站工作台同款，按菜单 id 稳定取色 */
const ICON_COLORS = ['#409eff', '#00b578', '#ff8f1f', '#fa4b4b', '#8f68f4', '#0aa8a4', '#f0a020', '#5a8dee']
function iconBg(e: WbEntry) {
  return ICON_COLORS[Math.abs(e.seed ?? 0) % ICON_COLORS.length]
}

/** 外部应用无菜单 id，用路径哈希当配色种子（同一应用颜色稳定） */
function hashSeed(s: string) {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0
  return h
}

async function loadWorkbench() {
  const p = await mse.profile()
  const menus: { path: string; name: string; icon?: string; group?: string; sub?: string; menuId?: number }[] = []
  // 目录层级随行携带：顶层目录=分组、二级目录=子组（与移动端基站 buildWorkbenchGroups 同语义，更深层级拍平进子组）
  const walk = (nodes: any[], group?: string, sub?: string) => {
    for (const n of nodes ?? []) {
      const m = n.menu
      if (m?.type === 1 && n.children?.length) {
        walk(n.children, group ?? m.name, group ? (sub ?? m.name) : undefined)
        continue
      }
      if (m?.type === 3 && m?.path) {
        menus.push({ path: `/sub/${m.microName}${m.path}`, name: m.name, icon: m.icon || '', group, sub, menuId: m.id })
      }
      if (n.children?.length) walk(n.children, group, sub)
    }
  }
  walk(p.menus ?? [])
  // 租户配置的基址/图标/标题/排序/打开方式（/im/workbench 仅启用行；空 → 全量默认展示）
  let cfg: mse.WbCfgItem[] = []
  try {
    const vo = await mse.workbench()
    webBase = vo.webBaseUrl
    cfg = vo.items
  } catch { /* 配置不可用时回退默认 */ }
  const byPath = new Map(menus.map(m => [m.path, m]))
  if (!cfg.length) {
    state.entries = menus.map(m => ({
      entryType: 0, path: m.path, name: m.name,
      icon: effectiveIcon(undefined, m.icon, true), openMode: 0, authMode: 0,
      group: m.group || '常用', sub: m.sub || '', seed: m.menuId,
    }))
    return
  }
  // 配置存在时按配置集过滤并排序：菜单入口仍以本人菜单集做权限过滤（不在菜单集的不展示），
  // 外部应用入口为租户自配直达地址；停用行已由服务端过滤
  state.entries = cfg.flatMap(c => {
    const isMenu = (c.entryType ?? 0) !== 1
    const m = isMenu ? byPath.get(c.path) : undefined
    const icon = effectiveIcon(c.icon, m?.icon, isMenu)
    const openMode = c.openMode ?? 0
    if (!isMenu) {
      return [{
        id: c.id, entryType: 1, path: c.path,
        name: c.title || '外部应用', icon, openMode, authMode: c.authMode ?? 0,
        group: c.groupName?.trim() || '外部应用', sub: '', seed: hashSeed(c.path),
      }]
    }
    if (!m) return []
    return [{
      id: c.id, entryType: 0, path: m.path,
      name: c.title || m.name, icon, openMode, authMode: 0,
      group: c.groupName?.trim() || m.group || '常用', sub: m.sub || '', seed: m.menuId,
    }]
  })
}

/** mse 菜单路由完整地址：追加 bare=1 无壳直达（基座隐藏侧栏/顶栏/页签，子页面铺满；菜单 path 可能自带 query），
 *  并携带 IM 当前主题 mseTheme/msePrimary——基座 bare 页瞬态跟随容器观感（不落基座 localStorage），本站常规使用不受影响 */
function mseEntryUrl(e: WbEntry): string {
  const sep = e.path.includes('?') ? '&' : '?'
  return `${webBase}${e.path}${sep}bare=1&mseTheme=${state.theme}&msePrimary=${encodeURIComponent(imPrimary())}`
}

/** 解析入口最终 URL：mse 菜单走一次性 code 免登（无壳直达）；外部应用直连原样、免登授权换取带 code 的回调地址 */
async function resolveEntryUrl(e: WbEntry): Promise<string> {
  if (e.entryType === 1) {
    if (e.authMode === 1 && e.id != null) return await mse.wbJump(e.id)
    return e.path
  }
  return await mse.ssoJump(mseEntryUrl(e))
}

/** 兜底 URL（签发失败时直开，不做免登） */
function fallbackUrl(e: WbEntry): string {
  return e.entryType === 1 ? e.path : mseEntryUrl(e)
}

/** 点击宫格：按入口配置的打开方式呈现——内嵌到右侧 iframe（默认）或弹新窗口 */
async function openEntry(e: WbEntry) {
  if (e.opening) return
  e.opening = true
  try {
    const url = await resolveEntryUrl(e).catch(() => fallbackUrl(e))
    if (e.openMode === 1) {
      window.open(url, '_blank')
      return
    }
    state.activeEntry = e
    state.entryUrl = url
    // 切换入口即丢弃上一个内嵌页的路由上报，避免分享到错误的页面
    state.embedSubPath = ''
    state.embedTitle = ''
  } finally {
    e.opening = false
  }
}

/** 工具条「新窗口打开」：code 一次性且短时效，必须重新签发 */
async function openNewWindow() {
  const e = state.activeEntry
  if (!e || state.entryBusy) return
  state.entryBusy = true
  try {
    window.open(await resolveEntryUrl(e), '_blank')
  } catch {
    window.open(fallbackUrl(e), '_blank')
  } finally {
    state.entryBusy = false
  }
}

/* ---------- 页面分享（内嵌页 → 联系人/群聊卡片） ---------- */

type ShareTarget = { sourceID: string; sessionType: 1 | 3; name: string }

const embedFrame = ref<HTMLIFrameElement>()

/* ---------- 内嵌页主题跟随（初值随入口 URL 下发，切换经消息实时同步） ---------- */

/** IM 当前主色：CSS 变量为唯一色源（读计算值，改 --im-primary 即全链路生效） */
function imPrimary(): string {
  return getComputedStyle(document.documentElement).getPropertyValue('--im-primary').trim() || '#3370ff'
}

/** IM 切深浅时通知内嵌基座页实时跟随，免重载（基座仅 bare 模式消费，见 mse-ui Layout.vue） */
watch(() => state.theme, () => {
  embedFrame.value?.contentWindow?.postMessage({ type: 'mse:theme', theme: state.theme, primary: imPrimary() }, '*')
})

function openShare() {
  ensureContacts()
  state.showSharePicker = true
}

/** 分享给多选目标：卡片深链 = webBase + 内嵌页上报路由（未上报回落入口自身路径，仅页面级还原）。
 *  userIds 必须字符串透传：19 位雪花 ID 超出 Number 安全整数，强转会改写末几位致 OpenIM 找不到用户。
 *  icon 随卡片携带入口有效图标（配置 > 菜单 > Box），收件端按同款规则渲染、空回退 🧩 */
async function doSharePage(targets: ShareTarget[]) {
  const e = state.activeEntry
  if (!e || !targets.length) return
  state.shareBusy = true
  try {
    const sent = await mse.sharePage({
      userIds: targets.filter(t => t.sessionType === 1 && !isBotUserId(t.sourceID)).map(t => t.sourceID),
      groupIds: targets.filter(t => t.sessionType === 3).map(t => t.sourceID),
      title: state.embedTitle || e.name,
      desc: '工作台页面分享',
      url: `${webBase}${state.embedSubPath || e.path}`,
      icon: e.icon && e.icon !== DEFAULT_ICON ? e.icon : '',
    })
    state.showSharePicker = false
    setStatus(`页面已分享（${sent} 个目标）`)
    toast(`页面已分享（${sent} 个目标）`, 'success')
  } catch (err: any) {
    setStatus('分享失败：' + (err?.message || err))
    toast('分享失败：' + (err?.message || err), 'error')
  } finally {
    state.shareBusy = false
  }
}

/** 基座 bare 模式路由上报（mse-ui Layout.vue postMessage）：校验消息来源是内嵌 iframe 本体，防无关窗口伪造 */
function onEmbedMessage(ev: MessageEvent) {
  const d = ev.data as any
  if (!d || d.type !== 'mse:bare-route' || typeof d.subPath !== 'string' || !d.subPath) return
  if (embedFrame.value && ev.source !== embedFrame.value.contentWindow) return
  state.embedSubPath = stripTransientParams(d.subPath)
  if (typeof d.title === 'string' && d.title) state.embedTitle = d.title
}

/** 防御性摘除 ssoCode/bare/mseTheme/msePrimary——一次性凭证与容器主题都不应进卡片（sso code 绑定签发者会话） */
function stripTransientParams(subPath: string): string {
  const qIndex = subPath.indexOf('?')
  if (qIndex < 0) return subPath
  const kept = subPath.slice(qIndex + 1).split('&').filter(kv => kv && !/^(ssoCode|bare|mseTheme|msePrimary)=/.test(kv))
  return kept.length ? `${subPath.slice(0, qIndex)}?${kept.join('&')}` : subPath.slice(0, qIndex)
}

window.addEventListener('message', onEmbedMessage)

/* ---------- 导航切换 ---------- */

function switchTab(t: 'chat' | 'contacts' | 'workbench') {
  state.tab = t
  if (t === 'contacts') loadContacts()
  if (t === 'workbench') loadWorkbench().catch(() => { /* 保持现值 */ })
}

/** 联系人懒加载：组织架构/机器人（mse 接口）+ 我的群聊（SDK，容错保空态） */
async function loadContacts() {
  if (!sdk) return
  state.contactsLoading = true
  try {
    try { state.groups = unwrapArr(await sdk.getJoinedGroupList()) } catch { state.groups = [] }
    const c = await mse.getContacts()
    state.contacts.depts = c.depts
    state.contacts.users = c.users
    state.contacts.bots = c.bots
    state.contacts.deptGroupIds = c.deptGroupIds
    state.contactsLoaded = true
  } catch { /* 实例不可用等：保留现值，下次打开选择器重试 */ }
  finally { state.contactsLoading = false }
}

/** 通讯录按需加载（登录后预热一次；任一选择器打开时兜底）——不阻塞打开动作，数据到位即填充 */
function ensureContacts() {
  if (!state.contactsLoaded && !state.contactsLoading) loadContacts()
}

async function logout() {
  try { await sdk?.logout() } catch { /* 忽略 */ }
  mse.clearToken()
  mse.clearAuth()
  sdk = null; session = null
  callMgr.value = null
  lastSubscribed = ''
  Object.assign(state, {
    loggedIn: false, connected: false, conversations: [], activeConv: null, messages: [],
    draft: '', quoteMsg: null, historyEnd: false, loadingMore: false, members: [],
    progressMap: {}, typingMap: {}, onlineMap: {},
    groups: [], unreadTotal: 0,
    contacts: { depts: [], users: [], bots: [], deptGroupIds: [] }, contactsLoading: false, contactsLoaded: false, openDepts: [],
    showPicker: false, showCardPicker: false, drawerVisible: false, drawerGroupId: '', drawerGroupName: '',
    profile: { visible: false, mode: 'user' as 'user' | 'bot' | 'self', userId: '', bot: null as mse.ContactBot | null },
    entries: [], activeEntry: null, entryUrl: '', entryBusy: false, filter: '',
    embedSubPath: '', embedTitle: '', showSharePicker: false, shareBusy: false,
    selectMode: false, selectedIds: [], mergePreviewMsg: null, viewer: null, searchVisible: false,
    linkForm: null, rtc: { enabled: false, url: '' }, settingsOpen: false,
    status: '未连接',
  })
  msgMenu.value = null
  convMenu.value = null
  forwardState.value = null
}

onBeforeUnmount(() => {
  window.removeEventListener('message', onEmbedMessage)
  if (emailTimer) clearInterval(emailTimer)
  try { sdk?.logout() } catch { /* 忽略 */ }
})
</script>

<style>
:root {
  --im-primary: #3370ff;
  --im-bg: #f5f6f8;
  --im-rail-bg: #e9ebf0;
  --im-panel-bg: #ffffff;
  --im-content-bg: #f5f6f8;
  --im-border: #eceef1;
  --im-text: #303133;
  --im-text-2: #8a8f99;
  --im-hover: #f2f3f5;
  --im-active: #e8efff;
  --im-bubble-other: #ffffff;
  --im-bubble-mine: #d6e4ff;
  --im-input-bg: #f2f3f5;
  --im-badge: #fa5151;
  --im-green: #34c777;
  --im-card: #ffffff;
  --im-shadow: rgba(0, 0, 0, .06);
}
html[data-theme='dark'] {
  --im-primary: #3370ff;
  --im-bg: #111213;
  --im-rail-bg: #1a1b1e;
  --im-panel-bg: #202124;
  --im-content-bg: #17181a;
  --im-border: #2e2f33;
  --im-text: #e6e8ea;
  --im-text-2: #9aa0a8;
  --im-hover: #2a2c2f;
  --im-active: #1c2b45;
  --im-bubble-other: #2a2c2f;
  --im-bubble-mine: #1d3a75;
  --im-input-bg: #2a2c2f;
  --im-badge: #fa5151;
  --im-green: #34c777;
  --im-card: #202124;
  --im-shadow: rgba(0, 0, 0, .45);
}

* { box-sizing: border-box; }
html, body { margin: 0; height: 100%; }
body {
  font-family: system-ui, 'Segoe UI', sans-serif;
  background: var(--im-bg);
  color: var(--im-text);
  font-size: 14px;
}
::-webkit-scrollbar { width: 6px; height: 6px; }
::-webkit-scrollbar-thumb { background: rgba(128, 134, 144, .35); border-radius: 3px; }
::-webkit-scrollbar-track { background: transparent; }

.app { height: 100vh; display: flex; flex-direction: column; }

/* ---------- 登录页 ---------- */
.login {
  margin: auto; width: 380px; background: var(--im-card); padding: 28px;
  border-radius: 12px; box-shadow: 0 4px 20px var(--im-shadow);
  display: flex; flex-direction: column; gap: 10px;
}
.login h2 { margin: 0 0 4px; }
.login label { display: flex; flex-direction: column; font-size: 13px; color: var(--im-text-2); gap: 4px; }
.login input { padding: 8px 10px; border: 1px solid var(--im-border); border-radius: 8px; background: var(--im-panel-bg); color: var(--im-text); outline: none; }
.login input:focus { border-color: var(--im-primary); }
.captcha-row { display: flex; gap: 8px; align-items: center; }
.captcha-row input { flex: 1; }
.captcha-img { height: 36px; border-radius: 6px; cursor: pointer; border: 1px solid var(--im-border); }
.totp-qr { width: 160px; height: 160px; align-self: center; border: 1px solid var(--im-border); border-radius: 8px; }

button { padding: 8px 14px; border: 1px solid var(--im-border); background: var(--im-panel-bg); color: var(--im-text); border-radius: 8px; cursor: pointer; font-size: 13px; }
button.primary { background: var(--im-primary); color: #fff; border-color: var(--im-primary); }
button:disabled { opacity: .5; cursor: default; }
.login button.ghost { background: transparent; }
.hint { color: var(--im-text-2); font-size: 12px; }
.error { color: #e34d59; font-size: 13px; margin: 0; }

/* ---------- 主界面三区 ---------- */
.main { flex: 1; display: flex; min-height: 0; }

/* 图标导航栏 */
.rail {
  width: 64px; flex: none; background: var(--im-rail-bg); border-right: 1px solid var(--im-border);
  display: flex; flex-direction: column; align-items: center; padding: 14px 0 12px; gap: 6px;
}
.me { position: relative; margin-bottom: 10px; cursor: default; }
.me.clickable { cursor: pointer; }
.me-face {
  width: 40px; height: 40px; border-radius: 10px; object-fit: cover; display: flex;
  align-items: center; justify-content: center; background: var(--im-primary); color: #fff; font-weight: 600; font-size: 17px;
}
.me-dot {
  position: absolute; right: -2px; bottom: -2px; width: 11px; height: 11px; border-radius: 50%;
  background: #c2c4c8; border: 2px solid var(--im-rail-bg);
}
.me-dot.on { background: var(--im-green); }
.rail-nav { display: flex; flex-direction: column; gap: 6px; }
.rail-bottom { margin-top: auto; display: flex; flex-direction: column; gap: 6px; }
.rail-btn {
  position: relative; width: 44px; height: 44px; padding: 0; border: none; background: transparent;
  border-radius: 10px; color: var(--im-text-2); display: flex; align-items: center; justify-content: center; cursor: pointer;
}
.rail-btn:hover { background: var(--im-hover); color: var(--im-text); }
.rail-btn.active { background: var(--im-primary); color: #fff; }
.rail-btn svg { width: 22px; height: 22px; fill: currentColor; }
.badge {
  position: absolute; top: 3px; right: 3px; min-width: 16px; height: 16px; padding: 0 4px;
  border-radius: 8px; background: var(--im-badge); color: #fff; font-size: 10px; line-height: 16px;
  font-style: normal; text-align: center; font-weight: 600;
}
.badge.gray { background: #b9bcc4; }

/* 列表面板 */
.panel { width: 268px; flex: none; background: var(--im-panel-bg); border-right: 1px solid var(--im-border); display: flex; flex-direction: column; min-height: 0; }
.searchbar {
  margin: 10px 10px 6px; display: flex; align-items: center; gap: 6px;
  background: var(--im-input-bg); border-radius: 8px; padding: 7px 10px;
}
.searchbar svg { width: 14px; height: 14px; fill: var(--im-text-2); flex: none; }
.searchbar input { border: none; background: transparent; outline: none; color: var(--im-text); flex: 1; font-size: 13px; }
.search-msg-btn { flex: none; border: none; background: transparent; cursor: pointer; font-size: 12px; padding: 0 2px; opacity: .7; }
.search-msg-btn:hover { opacity: 1; }
.list { flex: 1; overflow: auto; padding: 4px 8px 10px; min-height: 0; }
.empty-mini { color: var(--im-text-2); font-size: 12px; padding: 14px 10px; line-height: 1.6; text-align: center; }
.group-head {
  margin: 10px 0 2px; display: flex; align-items: center; gap: 6px; padding: 6px 8px;
  border-radius: 8px; cursor: pointer; user-select: none; font-size: 13px; color: var(--im-text);
}
.group-head:hover { background: var(--im-hover); }
.group-head .head-nm { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.group-head .cnt { font-size: 12px; color: var(--im-text-2); flex: none; }
.face { width: 38px; height: 38px; border-radius: 8px; flex: none; object-fit: cover; }
.face.char { background: var(--im-primary); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 600; font-size: 16px; }
.face.char.group { background: #00b04f; }
.face.char.bot { background: #00b04f; }
.face.sm { width: 32px; height: 32px; font-size: 14px; }
.face.click { cursor: pointer; }

/* 会话列表项 */
.conv { display: flex; gap: 10px; padding: 9px 8px; border-radius: 8px; cursor: pointer; align-items: center; }
.conv:hover { background: var(--im-hover); }
.conv.active { background: var(--im-active); }
.conv-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.conv-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.conv-name { font-weight: 600; font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pin-mark { font-style: normal; font-size: 11px; }
.mute-mark { font-style: normal; font-size: 11px; flex: none; }
.conv-time { flex: none; font-size: 11px; color: var(--im-text-2); }
.conv-preview { font-size: 12px; color: var(--im-text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.conv .badge { position: static; flex: none; }

/* 联系人列表项 */
.newchat-bar { padding: 2px 6px 6px; }
.link-btn { border: none; background: transparent; color: var(--im-text-2); padding: 6px 8px; border-radius: 6px; width: 100%; text-align: left; }
.link-btn:hover { background: var(--im-hover); color: var(--im-text); }
.item { display: flex; gap: 10px; padding: 7px 8px; border-radius: 8px; cursor: pointer; align-items: center; }
.item:hover { background: var(--im-hover); }
.item-name { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.item-tag { flex: none; font-size: 11px; color: var(--im-text-2); }

/* 组织架构树行 */
.dept-row {
  display: flex; align-items: center; gap: 6px; padding: 7px 8px; border-radius: 8px;
  cursor: pointer; font-size: 13px; color: var(--im-text-2); user-select: none;
}
.dept-row:hover { background: var(--im-hover); }
.dept-row .dept-nm { color: var(--im-text); font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dept-row .dept-cnt { font-size: 12px; color: var(--im-text-2); flex: none; }
.arrow {
  width: 16px; height: 16px; flex: none; display: inline-flex; align-items: center; justify-content: center;
  color: var(--im-text-2); transition: transform .15s ease;
}
.arrow svg { width: 12px; height: 12px; fill: none; stroke: currentColor; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; }
.arrow.open { transform: rotate(90deg); }

/* 会话头部 */
.conv-head-name { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.conv-head-name.clickable { cursor: pointer; }
.conv-head-actions { display: flex; gap: 2px; flex: none; }
.gh-more {
  flex: none; border: none; background: transparent; color: var(--im-text-2);
  font-size: 14px; line-height: 1; padding: 2px 8px; border-radius: 6px; cursor: pointer;
}
.gh-more:hover { background: var(--im-hover); color: var(--im-text); }
.typing-hint { font-style: normal; font-size: 11px; color: var(--im-green); font-weight: 400; margin-left: 8px; }
.online-hint { font-style: normal; font-size: 11px; color: var(--im-green); font-weight: 400; margin-left: 8px; }

/* 多选操作条 */
.sel-bar { flex: none; display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-top: 1px solid var(--im-border); background: var(--im-panel-bg); }
.sel-bar .hint { flex: 1; }
.sel-bar .sm { padding: 6px 12px; font-size: 12px; }

/* 消息定位高亮闪烁 */
.flash { animation: msgflash 1.6s ease; border-radius: 8px; }
@keyframes msgflash { 0%, 60% { background: var(--im-active); } 100% { background: transparent; } }

/* 工作台宫格（列表面板内，分组样式对齐移动端基站工作台） */
.list.grid { padding: 0 10px 14px; }
.grid-group { padding: 12px 2px 2px; font-size: 13px; color: var(--im-text-2); }
.grid-sub { padding: 8px 2px 0; font-size: 13px; font-weight: 600; color: var(--im-text); }
.grid-cells { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px 0; padding: 8px 0 4px; }
.cell {
  display: flex; flex-direction: column; align-items: center; gap: 6px;
  border: none; background: transparent; padding: 0; cursor: pointer; min-width: 0;
}
.cell:disabled { opacity: .6; }
.cell-icon {
  width: 44px; height: 44px; border-radius: 12px; color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 24px; line-height: 1; overflow: hidden;
  transition: transform .15s ease;
}
.cell:hover .cell-icon { transform: translateY(-2px); }
.cell-icon img { max-width: 28px; max-height: 28px; object-fit: contain; border-radius: 6px; }
.cell-icon svg { width: 22px; height: 22px; }
.cell.active .cell-name { color: var(--im-primary); }
.cell-name {
  font-size: 12px; color: var(--im-text); text-align: center; max-width: 100%; padding: 0 2px;
  word-break: break-all; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; line-height: 1.25;
}

/* 内容区 */
.content { flex: 1; min-width: 0; display: flex; flex-direction: column; background: var(--im-content-bg); }
.pane { flex: 1; display: flex; flex-direction: column; min-height: 0; min-width: 0; }
.conv-head { flex: none; display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 13px 18px; border-bottom: 1px solid var(--im-border); font-weight: 600; background: var(--im-panel-bg); min-width: 0; }

/* 工作台内嵌应用 */
.embed { flex: 1; display: flex; flex-direction: column; min-height: 0; }
.embed-bar {
  flex: none; display: flex; align-items: center; gap: 10px; padding: 6px 12px;
  background: var(--im-panel-bg); border-bottom: 1px solid var(--im-border);
}
.embed-bar .hint { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.embed-bar b { flex: none; }
.mini-btn { margin-left: auto; flex: none; padding: 4px 10px; font-size: 12px; border-radius: 6px; display: inline-flex; align-items: center; gap: 5px; }
/* 相邻按钮共用右侧一组：只有首个吃 auto 边距，后续按钮紧挨排布 */
.mini-btn + .mini-btn { margin-left: 6px; }
.mini-btn svg { width: 13px; height: 13px; flex: none; }
.embed-frame { flex: 1; width: 100%; border: none; background: var(--im-panel-bg); }

/* 链接卡片表单 */
.lf-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, .35); z-index: 66; display: flex; align-items: center; justify-content: center; }
.lf { width: 360px; max-width: 92vw; background: var(--im-panel-bg); border-radius: 12px; box-shadow: 0 8px 32px var(--im-shadow); padding: 16px; display: flex; flex-direction: column; gap: 10px; }
.lf-head { font-weight: 600; }
.lf label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--im-text-2); }
.lf input { padding: 7px 10px; border: 1px solid var(--im-border); border-radius: 8px; background: var(--im-input-bg); color: var(--im-text); outline: none; }
.lf input:focus { border-color: var(--im-primary); }
.lf-foot { display: flex; justify-content: flex-end; gap: 8px; }

/* 空态占位 */
.placeholder {
  flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 12px; color: var(--im-text-2); min-height: 0;
}
.placeholder svg { width: 56px; height: 56px; fill: var(--im-border); }
.placeholder p { margin: 0; font-size: 13px; }

/* ---------- 桌面端（登录服务器入口 / 设置弹层） ---------- */
.setup-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.setup-link { flex: none; padding: 2px 0; border: none; background: transparent; color: var(--im-text-2); font-size: 12px; }
.setup-link:hover { color: var(--im-primary); }
/* 满屏设置页：整窗覆盖，顶栏标题 + 居中内容列 */
.set-mask { position: fixed; inset: 0; z-index: 70; background: var(--im-panel-bg); display: flex; flex-direction: column; }
.set-head {
  flex: none; display: flex; align-items: center; justify-content: space-between;
  padding: 14px 20px; border-bottom: 1px solid var(--im-border);
}
.set-title { font-weight: 600; font-size: 15px; }
.set-close {
  width: 30px; height: 30px; padding: 0; border: none; background: transparent; border-radius: 8px;
  color: var(--im-text-2); font-size: 13px; cursor: pointer; display: flex; align-items: center; justify-content: center;
}
.set-close:hover { background: var(--im-hover); color: var(--im-text); }
.set-body { flex: 1; overflow: auto; padding: 28px 20px; display: flex; flex-direction: column; align-items: center; gap: 16px; }
.set-card {
  width: min(560px, 100%); background: var(--im-input-bg); border: 1px solid var(--im-border);
  border-radius: 12px; padding: 16px; display: flex; flex-direction: column; gap: 14px;
}
.set-sec-title { font-weight: 600; font-size: 14px; padding-bottom: 10px; border-bottom: 1px solid var(--im-border); }
.set-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 14px; }
.set-label { color: var(--im-text-2); flex: none; }
.set-val { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; text-align: right; font-size: 13px; }
.set-btn { align-self: flex-start; padding: 8px 16px; font-size: 13px; }
.set-btn.danger { color: #e34d59; border-color: currentColor; background: transparent; }
.set-logout { align-self: center; width: min(560px, 100%); padding: 11px 16px; }
.set-switch { position: relative; display: inline-block; width: 40px; height: 22px; flex: none; cursor: pointer; }
.set-switch input { display: none; }
.set-switch i { position: absolute; inset: 0; border-radius: 11px; background: #c8cbd2; transition: background .15s ease; }
.set-switch i::after { content: ''; position: absolute; top: 2px; left: 2px; width: 18px; height: 18px; border-radius: 50%; background: #fff; transition: transform .15s ease; }
.set-switch input:checked + i { background: var(--im-green); }
.set-switch input:checked + i::after { transform: translateX(18px); }
</style>
