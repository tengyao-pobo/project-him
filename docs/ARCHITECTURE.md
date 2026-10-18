# 架構與設計決策

## 總覽

```
               ┌──────────────── Him（服務容器，唯一的 tick 迴圈）────────────────┐
 Bukkit 事件 → │ PlayerListener / EntityListener                                    │
               │        │                                                           │
               │  SessionManager ──ContextSampler──► PlayerContext（不可變快照）    │
               │        │                     └──► 記憶：熱點、行為、同伴           │
               │  DirectorService ──► HorrorDirector（純邏輯）──► DirectorDecision   │
               │        │                 ├ TensionTracker（張力節奏）              │
               │        │                 ├ GlobalBudget（全服預算）                │
               │        │                 └ StageCalculator / Learning              │
               │  EventManager ──► EventInstance（每個進行中的遭遇）                │
               │        │            ├ Apparition（Mannequin 本體 + 玩家反應）      │
               │        │            ├ HimCarrier + GridPathfinder + ChaseBrain     │
               │        │            ├ SiegeWorks（圍困：破壞／墊高，經回滾日誌）  │
               │        │            └ FakeBlockService / WorldChangeJournal        │
               │  SignService ──► SignReader / SignReplies（純邏輯，告示牌對話）    │
               │  MemoryService（快取 + 單一 IO 執行緒）── JsonMemoryRepository     │
               │  PerformanceGovernor（降級策略）                                   │
               └────────────────────────────────────────────────────────────────────┘
```

**純邏輯與伺服器分離**：`director`（除 `DirectorService`）、`chase.ChaseBrain`、`chase.HuntGear`、`nav.GridPathfinder`、`dialogue.SignReader`／`SignReplies`、`memory`（除 `MemoryService` 的排程）不碰任何伺服器狀態，可在 JUnit 中完整測試。所有版本相關的 API 使用集中在 `entity`、`platform`、`sense`、`world`。

**執行緒模型**：所有遊戲狀態只在伺服器主執行緒存取。檔案 IO 在單一 `HIM-memory-io` 執行緒；要存檔的物件先在主執行緒序列化成 JSON 字串再交給 IO 執行緒，因此 IO 執行緒永遠不會看到可變物件；單一執行緒也保證了「刪除檔案 → 重新載入」等操作的順序。

## 關鍵決策（以及為什麼）

### 1. 外觀：Mannequin + 原生可見性 API，不用封包庫
Paper 26.3 提供 `Mannequin`（可設定 `ResolvableProfile` 皮膚的類玩家實體）與 `Entity#setVisibleByDefault` / `Player#showEntity`。後者讓「只有某些玩家看得到」完全在伺服器端以官方 API 完成，不需要 ProtocolLib／PacketEvents，也不需要偽造玩家清單項目。生成時在 `world.spawn(..., consumer)` 的 consumer 內就設為隱藏，避免任何一幀被其他玩家看到。

皮膚：原版客戶端拒絕未簽名的玩家皮膚，因此預設設定內嵌一份 **Mojang 簽名**的經典白眼皮膚 textures（取自 MineSkin 公開資料庫，已下載貼圖並逐一比對臉部像素選出經典 Steve 體型）。管理員可替換成任何簽名皮膚。

### 2. 追殺移動：虛擬載體 + 自製 A*（實測後的改變）
最初設計是用一隻隱形、移除 AI 目標的殭屍跑原版尋路，Mannequin 跟隨。**在真實伺服器自我測試中發現它完全不動**：Spigot/Paper 的實體啟動範圍會讓遠離玩家的生物幾乎不運算；改成無 AI 時，原版 `findPath` 又要求生物「站在地上」才肯規劃，而不運算的生物永遠不會更新這個狀態。

因此改為：
- `GridPathfinder`：玩家大小身體的 A*（8 方向且不切角、上一格、下落三格、游泳、木門與柵欄門、避開危險方塊），節點上限 3000，無法到達時回傳最接近目標的部分路徑，未載入區塊視為牆。
- `HimCarrier`：純虛擬位置，沿路徑以精確速度移動。

好處：速度精確（不需要換算移動屬性）、不受啟動範圍影響、世界中不存在任何隱形生物、可完整單元測試。代價：不模擬實體碰撞與擊退物理（追殺中的擊退改由大腦的「硬直」狀態表現）。

### 3. 導演：危險率 + 張力節奏 + 效用 AI
- **危險率**（Weibull，形狀 2）：「安靜越久越可能發生」，間隔有統計規律但不可預測，不是排程。
- **張力**：事件增加張力（半衰期 15 分鐘）；在累積期強度上限隨張力打開，所以最強烈的事件（追殺）自然成為高潮；達到每輪隨機的門檻後進入高峰（只允許輕微餘波），再進入放鬆期（幾乎完全沉默，直到張力降到 0.25 以下）。模擬與實際參數調整過程記錄在 `HorrorDirectorTest`。
- **效用 AI**：意圖分數來自性格、玩家性情（無視的人得到更多存在感，逃跑的人被更多跟蹤）、情境（夜晚、地下、獨處、基地附近）。
- **新鮮度**：近期出現過的事件與意圖被降權，第一次見到的事件加權。
- **破格**：同一規律被確認多次後，依破格傾向有機率讓下一次視覺事件改變結局，並有最小間隔，讓破格保持稀有。

### 4. 學習
`Learning` 對每次遭遇結果做小幅、有上下限的調整；性格每天向基準值回歸一定比例，所以 HIM 會隨伺服器玩家的行為慢慢改變，但不會被單次事件扭曲。追殺會依玩家的**慣用逃脫方式**做出反制（不超過設定上限）。

### 5. 世界安全
- 假方塊只對單一客戶端；固體假方塊與玩家保持距離並在靠近時還原；玩家互動到假方塊時立即還原；實際方塊改變時放棄紀錄。
- 真實修改經過 `ProtectionService`（天然方塊、遠離出生點／床／推測基地／建築區、`mobGriefing`），先寫日誌再修改，只在無人觀看時、且方塊仍是 HIM 放置的狀態時還原；關服立即還原已載入部分。

### 6. 容錯
- 每個事件實例的例外都被隔離：結束該實例並完整清理，不影響其他事件或 tick 迴圈。
- 每個實例都有硬性時間上限。
- 實體登錄確保所有身影可追蹤；區塊載入時清除帶標記但未登錄的殘留。
- 設定檔錯誤一次列出全部，重新載入失敗保留舊設定；首次啟動若設定錯誤，插件保持休眠，修正後 `/him reload` 即可啟動。

### 7. 追殺漏洞（v1.1.0）
每一種「讓他到不了」的方法都由 `ChaseBrain` 的狀態處理，而不是讓 HIM 作弊：
- **SIEGE**：靠近、最近看得到、但路徑到不了（`lastPlanReachedGoal` 為假，或距離近卻沒有進展）持續 1.5 秒 → 圍困。實際的破壞／墊高在 `SiegeWorks`，一次一個動作、玩家看得到裂痕、每場有上限，全部經過 `EntityChangeBlockEvent` 與回滾日誌。
- **近距離感知**：6 格內、玩家不在真正室內時，不需要視線也算「看得到」。1×1 坑的漏洞是實測時發現的。
- **AIRBORNE / LANDING**：鞘翅飛行時他不追（他不會飛），隱身並記錄飛行時間；落地時控制器把他放在玩家看不到的附近。累計飛行時間而非單次，避免「落地再起飛」重置。
- **傳送跟隨**：`EventManager.participantLost` 讓實例可以「保留」參與者（`retains`），追殺在傳送後不結束，數秒後在新位置（必要時是另一個世界的新本體）繼續；跨世界不移動實體，而是在目標世界重新生成本體，避免實體跨世界的副作用。
- 刻意保留的公平解法：真正的室內（屋頂＋燈光）、長時間飛行、多次傳送、地獄門、擊退、時限。

### 8. 告示牌對話
回覆不修改世界：以 `FakeBlockService.showSign` 只送給寫字的人，紀錄存在玩家記憶（`SignDialogue`）中，玩家回到附近時重新送出，因此跨重啟仍在。告示牌被改寫或打掉（文字雜湊不同）即結束這段對話。

## 擴充新事件

1. 在 `event/impl` 新增一個 `HorrorEvent`：用 `EventSpec.builder` 描述意圖、最低階段、強度、冷卻、支援模式、觸發方式、硬性條件與適配度。
2. 實作 `EventInstance`（視覺事件可繼承 `VisualInstance`，純聲音事件可繼承 `AudioInstance`）：`start()`、`tick()`、`cleanup()`。只要在 `cleanup()` 撤銷自己建立的一切即可，假方塊會由事件管理器額外保險還原。
3. 加入 `EventCatalog.create()`。導演會自動把它納入選擇，`/him events`、`/him trigger`、設定檔的 `disabled-events` 也會自動支援。
