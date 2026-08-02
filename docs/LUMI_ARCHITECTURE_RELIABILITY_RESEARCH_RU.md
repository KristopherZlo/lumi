# Научно-техническая модель архитектуры, производительности и надёжности Lumi

## Аннотация

Документ описывает фактически реализованную систему Lumi V2 для Minecraft 1.21.11 и задаёт воспроизводимый протокол исследования времени Save/Restore, нагрузки на серверный тик, потребления памяти, роста репозитория и устойчивости к прерыванию операций. Анализ кода зафиксирован на ревизии `9011a1de217b176586ad3c8982086f699158eeb9` от 2026-07-29 (`Reduce cold object metadata probes`), версия мода `0.2.0-rc.1`, Java 21. Локальные UI-изменения вне этой ревизии не входят в доказательную базу; tracked core-код `domain`, `storage` и `minecraft` совпадает с указанной ревизией. Проектные требования, выводы из кода, тестовые свидетельства и исторические измерения рассматриваются раздельно.

Главный вывод: Lumi реализует версионирование минимального собственного состояния мира через неизменяемые content-addressed-объекты, разреженное Merkle-дерево, CAS-публикацию ссылок и журнал восстановления. Эта схема хорошо защищает уже опубликованную историю и позволяет сравнивать конечные состояния независимо от числа промежуточных версий. Основной нерешённый предел — полная задержка крупного Restore: последние исторические измерения соблюдают 50 ms tick-gate, но не соблюдают заданные 750 ms для application и 3 s для полного профиля `512×512×16`; на JVM с heap 4 GiB измеренный кандидат также превысил лимит дополнительной памяти 1 GiB.

## 0. Исследовательская постановка и статус доказательств

Документ отвечает не на вопрос «работает ли Lumi вообще», а на следующие исследовательские вопросы:

- `RQ1`: какие инварианты предотвращают потерю уже опубликованной истории при crash в каждой фазе Save и Restore?
- `RQ2`: соответствует ли опубликованный Save наблюдаемому состоянию блоков, block entities, durable entities и respawn points на зафиксированной capture boundary?
- `RQ3`: какими параметрами определяется стоимость Save, Compare и Restore: числом commits, endpoint delta, пространственной локальностью, chunk path или durable barriers?
- `RQ4`: ограничена ли живая память размером текущего batch/slab при росте полного operation volume и истории?
- `RQ5`: эквивалентна ли idle-нагрузка Lumi vanilla в заранее заданных практически значимых пределах?
- `RQ6`: какие архитектурные решения следует сохранить, изменить или отклонить по совокупности correctness, crash safety, latency, memory и maintainability?

Типы свидетельств имеют строгий порядок силы:

1. `CODE` — свойство непосредственно следует из control/data flow указанной ревизии.
2. `UNIT` — свойство проверено детерминированным тестом отдельных collaborators.
3. `INTEGRATION` — свойство проверено реальным Minecraft/Fabric workflow и независимым oracle.
4. `FAULT` — свойство пережило принудительное завершение процесса в конкретной durable boundary.
5. `MEASURED` — опубликованы raw samples, среда, повторения и неопределённость.
6. `REQUIREMENT` — желаемая гарантия без достаточного результата; она не считается доказанной.

| ID | Проверяемое утверждение | Нулевая гипотеза или falsifier | Текущий статус |
| --- | --- | --- | --- |
| `C1` | Save равен capture boundary | существует хотя бы одно несовпадение owned state | `CODE` + частичное `INTEGRATION` |
| `C2` | старый опубликованный commit не теряется при crash | после recovery commit/ref/object не читается или изменён | `CODE` + `UNIT`; phase-exhaustive `FAULT` отсутствует |
| `C3` | поздняя generation переживает clear старой операции | `g' > g` удалена publication boundary `g` | `CODE` + `UNIT` |
| `C4` | Compare не зависит от числа промежуточных commits | при постоянных endpoints/delta время линейно растёт с history length | `CODE`; контролируемый scaling experiment требуется |
| `C5` | Restore terminal success равен target | persisted reread или reopen не совпадает с oracle | `CODE` + `UNIT` + частичное `INTEGRATION` |
| `C6` | память ограничена текущим slab/batch | retained live set растёт с полным operation volume | structural `CODE`; 4 GiB occupancy gate провален |
| `C7` | idle Lumi эквивалентен vanilla | разность выходит за preregistered equivalence margin | только `REQUIREMENT` |
| `C8` | крупный Restore проходит release budget | любой обязательный latency/heap/tick gate превышен | предварительно опровергнуто историческими измерениями |

Ни один исторический benchmark не получает статус `MEASURED` для ревизии `9011a1de`, пока он не повторён на чистом checkout этой ревизии с сохранёнными raw-артефактами. Таблица раздела 9 является предварительной базой для гипотез, а не результатом текущего артефакта.

## 1. Объект исследования и границы точности

Lumi ведёт отдельный репозиторий для каждого измерения Minecraft по пути `<world>/lumi/history/<dimension>/`. Workspace и zone являются областями выбора над общим репозиторием и не создают копий блоков, сущностей или деревьев. Branch — изменяемая ссылка на commit; commit — неизменяемая запись с корнем дерева мира, 0–2 родителями, автором, временем, workspace, необязательной zone, статистикой и точками возрождения игроков.

Версионируемое состояние состоит из:

- block state каждого блока выбранных секций `16×16×16`;
- canonical NBT block entity, привязанного к локальному индексу блока;
- durable non-player entities, сгруппированных по chunk и упорядоченных по UUID;
- canonical player respawn points для полного Restore.

В commit не сохраняются биомы, световые данные, heightmap, POI и scheduled ticks как независимое историческое состояние. При Restore неизменяемые биомы сохраняются из live/vanilla chunk, производные heightmap и POI перестраиваются по изменённым клеткам, а для chunk с изменением света Lumi удерживает соседний halo, дожидается vanilla relight и явно синхронизирует итоговый свет с клиентами. Поэтому «точность Restore» в этом документе означает равенство принадлежащего Lumi состояния: block states, block entities, durable entities и, для полного Restore, respawn points; она не означает снимок всех подсистем Minecraft.

Логический oracle состояния задаётся как canonical tuple:

`W = (B, BE, E, P)`,

где `B` — отображение block coordinate → block state, `BE` — coordinate → canonical block-entity NBT, `E` — UUID → `(entity type, canonical NBT, chunk placement)`, `P` — player UUID → canonical respawn point. Для partial Restore сравниваются только выбранные `B` и `BE`; `E` и `P` должны остаться неизменными. Для zone Restore сравнение ограничено zone cells и entity columns, respawn points не входят в target.

Первичные реализации: [Commit](../src/main/java/io/github/lumi/domain/model/Commit.java), [SectionBlob](../src/main/java/io/github/lumi/domain/model/SectionBlob.java), [EntityChunkBlob](../src/main/java/io/github/lumi/domain/model/EntityChunkBlob.java), [storage-format.md](storage-format.md).

## 2. Архитектурная декомпозиция

| Слой | Реальная ответственность | Ключевые владельцы |
| --- | --- | --- |
| `domain/model` | неизменяемые значения, commit, ключи, деревья, journal, working index | `Commit`, `SectionBlob`, `OperationJournal`, `WorkingIndex` |
| `domain/service` | правила Save, Compare, Restore, recovery, merge и retention | `SaveService`, `CompareService`, `RestoreService`, `RecoveryService`, `ThreeWayMerge` |
| `storage/*` | canonical codec, SHA-256 identity, LZ4, pack/index, refs, journal, atomic replacement | `ObjectStore`, `ObjectPack`, `WorldObjectRepository`, repository-классы |
| `minecraft/world` | capture/apply, chunk readiness, native section rewrite, persistence и reread | `MutationDurabilityTracker`, `MinecraftWorldStateApply`, `StreamingPreparedWorldMutationSession` |
| `minecraft/operation` | сериализация операций, freeze, progress, terminal state | `DimensionOperationCoordinator`, `SaveCaptureOperation`, `RestoreOperation` |
| `minecraft/runtime` | сборка зависимостей и жизненный цикл измерения/сервера | `FabricDimensionRuntime`, `FabricServerSession` |
| `network`, `client/*` | ref-guarded команды, immutable payload, UI-контроллеры и визуализация | `LumiServerNetworking`, `HistorySnapshotPayload`, `LumiClientNetworking` |

Граница платформы фактически соблюдается: `domain/*` и `storage/*` не импортируют `net.minecraft` или `net.fabricmc`; Minecraft-типы сосредоточены в adapters/runtime/mixins. Это снижает стоимость unit-тестирования и соответствует dependency inversion. Одновременно [FabricDimensionRuntime](../src/main/java/io/github/lumi/minecraft/runtime/FabricDimensionRuntime.java) содержит 2011 строк и около 192 объявлений полей/методов, а [RestoreOperation](../src/main/java/io/github/lumi/minecraft/operation/RestoreOperation.java) — 934 строки. Они остаются точками концентрации orchestration-изменений и повышают риск нарушения SRP, хотя тяжёлая логика вынесена в сервисы и adapters.

Полная карта владельцев приведена в [modules.md](../modules.md); архитектурное описание — в [architecture.md](architecture.md).

## 3. Математическая модель данных

### 3.1. Canonical identity

Для canonical payload `p` идентификатор объекта равен:

`ID(p) = hex_lower(SHA-256(p))`.

Перед чтением packed/loose object Lumi проверяет magic, заявленные длины, безопасно выполняет LZ4-decompression и повторно вычисляет `ID(p)`. Одинаковые payload имеют один ID и дедуплицируются; различающиеся canonical payload считаются разными объектами. SHA-256 здесь является механизмом идентичности и обнаружения повреждения, но код корректно оставляет отдельную проверку равенства payload при обнаружении уже существующего ID, то есть коллизия не принимается молча. Несколько index могут временно указывать на одинаковый content-addressed object во время безопасной замены pack; reader обновляет каталог, если выбранный pack уже заменён.

Секция содержит `B = 16³ = 4096` block states. Локальный индекс:

`i(x,y,z) = (y << 8) | (z << 4) | x`, где `0 ≤ x,y,z < 16`.

Codec сортирует palette лексикографически, записывает 4096 unsigned 16-bit индексов и сортирует block entities по `i`. Базовый массив индексов занимает ровно `4096·2 = 8192` bytes до LZ4, не считая palette, заголовка и NBT. Реализация: [SectionBlobCodec](../src/main/java/io/github/lumi/storage/object/SectionBlobCodec.java).

### 3.2. Разреженное Merkle-дерево

Иерархия состояния:

`DimensionTree → RegionTree → ChunkTree → {SectionBlob, EntityChunkBlob}`.

Region содержит `32×32` chunks. Для chunk `(cx,cz)`:

`rx = floorDiv(cx,32)`, `rz = floorDiv(cz,32)`,

`lx = floorMod(cx,32)`, `lz = floorMod(cz,32)`.

Пусть Save изменил `D` leaf-объектов в `C` chunks и `R` regions. [MerkleTreeEditor](../src/main/java/io/github/lumi/storage/repository/MerkleTreeEditor.java) записывает не более `D + C + R + 1` логических объектов до дедупликации: новые leaves, по одному изменённому `ChunkTree`, по одному `RegionTree` и один `DimensionTree`. Неизменённые поддеревья повторно используются по ID. История поэтому растёт по числу уникальных изменённых payload и затронутых путей дерева, а не по полному объёму измерения.

Фактическое число шагов progress для перестройки дерева равно `C + R + 1`. Асимптотика structural update: `O(D + C + R)` записей и `O(C + R)` чтений старых узлов; кодирование секций добавляет `O(4096·S + E_nbt)`, где `S` — число записываемых секций.

### 3.3. Pack storage

Один Save помещает captured leaves и изменённые Merkle nodes в один `LUP2` pack. Pack сначала force-записывается, атомарно перемещается, полностью перечитывается с hash-проверкой, после чего атомарно публикуется sorted `LUI2` index. Только index делает pack видимым другим repository instances. Crash до index оставляет недостижимый pack, удаляемый GC после cutoff. После reachability-сборки idle GC переносит не более 4096/64 MiB raw удержанных legacy `LUO2` world objects в один pack: сначала публикует index, сохраняет наиболее новый source timestamp для retention, затем удаляет loose source. Он также объединяет bounded-группы мелких immutable packs: полностью проверенный новый pack/index публикуется до удаления старых index, поэтому crash на любой фазе оставляет хотя бы одну доступную копию каждого object. Grace-only history остаётся loose. Частично живой pack не уменьшается отдельно: он удаляется только если collectable все его objects, но полностью живые мелкие packs могут быть объединены без изменения object ID.

После открытия измерения scheduler пытается выполнить pack compaction через 200 ticks и повторяет попытку после временного отказа. Pending world changes ему не мешают, но recovery и queued/active mutation откладывают попытку. Полный reachability GC сохраняет исходную задержку 6000 ticks и часовой интервал.

Верхние границы формата: payload одного object 256 MiB, pack не более 1,000,000 entries. Реализации: [ObjectStore](../src/main/java/io/github/lumi/storage/object/ObjectStore.java), [ObjectPack](../src/main/java/io/github/lumi/storage/object/ObjectPack.java), [ObjectPackIndex](../src/main/java/io/github/lumi/storage/object/ObjectPackIndex.java).

Для pack с `n` новыми entries физический metadata overhead без filesystem allocation равен:

`M_pack_index(n) = 8 + 40n + 8 + 48n = 16 + 88n bytes`.

Здесь первые `8+40n` принадлежат pack, вторые `8+48n` — index; compressed payload bytes добавляются отдельно. Поэтому измеренная `bytes/block` зависит от palette, NBT, дедупликации, LZ4, количества Merkle nodes и pack occupancy и не является универсальной константой Lumi.

### 3.4. Формальная модель операций

Состояние dimension представляется как:

`X = (W, H, R, G, J, V, F, Q)`,

где `W` — owned logical world state, `H` — immutable commit/object graph, `R` — refs и active pointers, `G` — working/builder generations, `J` — operation journal, `V` — физическое vanilla storage, `F` — freeze state, `Q` — dimension operation queue.

Обязательные инварианты:

- `I1 Object integrity`: для каждого читаемого object `hash(payload)=id`, type codec принимает весь payload без trailing bytes.
- `I2 Reachability`: каждый опубликованный ref/pointer разрешается в существующий commit и существующий Merkle root.
- `I3 Save boundary`: опубликованный commit содержит ровно `W` на захваченных keys/generations и не очищает keys вне boundary.
- `I4 Generation monotonicity`: для одного key `g_{k,n+1}>g_{k,n}`; clear boundary `g` не удаляет `g'>g`.
- `I5 Publication order`: Restore не изменяет `R` до persisted verification target; Save не изменяет ref до durable commit и journal.
- `I6 Terminal exactness`: `COMPLETE ⇒ W=target`; `RETURNED ⇒ W=checkpoint`.
- `I7 Fail closed`: если ни target, ни checkpoint не доказаны, то `F=frozen` и `J≠∅`.
- `I8 Single mutation`: одновременно существует не более одной active mutating operation на dimension.
- `I9 Bidirectional scope`: target/return планы имеют одинаковые множества section/entity keys.
- `I10 Restart idempotence`: повторное открытие уже восстановленного состояния не выполняет publication или apply второй раз.

`I1`, `I4`, `I5`, `I7`, `I8` и `I9` имеют прямые code paths и unit tests. `I2`, `I3`, `I6` и `I10` требуют интегрированного oracle и fault injection для каждой durable boundary. Формальная модель должна рассматривать crash как переход `X → persisted(X)`, удаляющий volatile cursors/futures, но сохраняющий forced files, vanilla storage и атомарно опубликованные pointers. Этот конечный автомат следует независимо закодировать в PlusCal/TLA+ или эквивалентном model checker с crash-переходом после каждой durable action; model checking дополняет, но не заменяет Minecraft fault runs.

## 4. Модель capture и Save

До первой live-мутации секции mixin захватывает её pre-mutation origin. [MutationDurabilityTracker](../src/main/java/io/github/lumi/minecraft/world/MutationDurabilityTracker.java) назначает ключу строго возрастающую generation, удерживает chunk ticket и запрещает vanilla chunk/entity publication, пока origin и соответствующее состояние working index не стали durable. Повторная мутация того же ключа увеличивает generation; publication operation очищает ключ только при совпадении захваченной generation, поэтому поздняя мутация не теряется.

`WorkingIndex = {HistoryKey → generation}`; builder generations являются generation-aligned подмножеством того же атомарно заменяемого `LWI3`. Ambient change остаётся durable dirty state, но не обязана считаться builder draft. Максимальный persisted index — 1,000,000 keys; session preview хранит не более 16,384 последних block positions.

Save выполняется в следующем порядке:

1. `SavePreparation` фиксирует выбранную durability boundary и ждёт её durable-состояния.
2. Координатор удерживает dimension freeze; `WorldStateCapture` инкрементально копирует видимое состояние выбранных keys.
3. Capture проверяет точное равенство возвращённых и исходных generations.
4. Freeze снимается при переходе `SaveCaptureOperation` в `WRITING`; pack, commit, tags, journal, ref и clear working index публикуются на background executor.
5. `SaveService` создаёт journal `COMMIT_WRITTEN`, выполняет `refs.compareAndSet(expectedRef, commitId)`, переводит journal через `REF_PUBLISHED` и `COMPLETE`, durable-очищает captured generations и удаляет journal.

Таким образом, `t_freeze_save = t_prepare_tick + t_capture_tick`, а `t_save_total = t_freeze_save + t_pack + t_commit + t_ref + t_index`; вторая часть не блокирует симуляцию. CAS проверяет одновременно branch name, commit и monotonic revision, поэтому устаревшая команда не перезаписывает новый HEAD.

Реализации: [SaveCaptureOperation](../src/main/java/io/github/lumi/minecraft/operation/SaveCaptureOperation.java), [SaveService](../src/main/java/io/github/lumi/domain/service/SaveService.java), [BranchRefRepository](../src/main/java/io/github/lumi/storage/repository/BranchRefRepository.java).

## 5. Compare, Restore и Merge

### 5.1. Compare

[CompareService](../src/main/java/io/github/lumi/domain/service/CompareService.java) сравнивает ID корней, затем только несовпадающие region IDs, chunk IDs и leaves. Отсутствующий leaf разрешается через create-once origin. Для endpoint-разности с `RΔ` разными regions, `CΔ` разными chunks и `LΔ` разными leaves число структурных чтений имеет порядок `O(RΔ + CΔ + LΔ)`, а не `O(number_of_commits)`. Payload block sections на этой стадии не декодируются.

Block-level overlay требует последующего декодирования каждой изменённой пары секций и просмотра 4096 cells: `O(4096·SΔ)`. Network batches ограничены 4096 block coordinates. История между endpoints не replay-ится.

### 5.2. Restore

Примечание об актуальной реализации после зафиксированной в разделе 1 ревизии: глобальный
двунаправленный preflight по-прежнему декодирует и проверяет target и return до первой
мутации, но последующая подготовка batch повторно не декодирует уже проверенный base NBT.
В пределах slab лимит 128 применяется к холодным FULL-загрузкам, а уже resident chunks
объединяются в одно окно без повторных durability barriers. POI сохраняются только для
chunks с фактическими POI-изменениями. Ожидание vanilla lighting начинается до persistence:
chunk snapshots вне радиуса один от relight chunks могут записываться параллельно, но перед
записью затронутого lighting halo остаётся обязательный barrier. Финальный storage barrier
по-прежнему ждёт все поставленные vanilla writes, но physical force выполняет только для
region-файлов target chunk/POI/entity, а не для всего открытого кэша каждого `IOWorker`.
Если scoped accessor недоступен, операция явно возвращается к vanilla global
`synchronize(true)` и пишет warning; успех не публикуется без force. Метрики отдельно
показывают enqueue write, ожидание write barrier, physical force и persisted verification.
Persisted reread, точный reopen, return plan и journal publication protocol не изменены.
Для full Restore один bounded intent-prewarm может заранее построить immutable
source-to-target plan, выполнить двунаправленный preflight и запустить подготовку двух
64-MiB slabs. Runtime хранит не более одного такого плана на dimension. После hidden
return-point Save план используется только при равенстве Merkle tree исходного и checkpoint
commits. Актуальные player spawns берутся из durable checkpoint и подменяют только подготовленный
return state; поэтому безопасный возврат остаётся точным, даже если позиция respawn изменилась.
При различии tree stale-план закрывается и выполняется обычная точная подготовка от checkpoint.
После декодирования первой slab intent может на server thread
заранее удержать и загрузить её первое bounded chunk-окно. Тот же `ChunkLoadSession` передаётся
apply только после durable PREPARED journal; до журнала блоки, entities и vanilla storage не
изменяются. Отмена или stale revalidation освобождает все tickets.
Если intent отсутствует, branch switch запускает ту же подготовку параллельно hidden Save.
Фоновая работа и working-index durability могут использовать остаток текущего 30 ms
operation budget вместо ожидания следующего polling tick, но не расширяют deadline и не
меняют durable barriers. Streaming apply сообщает внешней операции, что exact verification,
force и persisted reread уже завершены, поэтому второй пустой verify-проход не создаётся.

[RestoreService](../src/main/java/io/github/lumi/domain/service/RestoreService.java) строит двунаправленный план `source ↔ target` только для несовпадающих Merkle leaves. Полный план включает durable entities и respawn points; partial plan исключает entities и spawns. Для частично пересекаемой секции selection выполняет ровно 4096 проверок и формирует новый `SectionBlob`; полностью выбранная секция переиспользует object.

Target и return plan имеют одинаковые множества keys — это constructor invariant [PreparedRestore](../src/main/java/io/github/lumi/domain/service/PreparedRestore.java). Payload загружаются лениво через одну read session на направление; LRU удерживает не более 32 decoded sections на направление.

Native preparation выполняется off-thread slabs. Для slab действуют три ограничения:

`sections ≤ 1024`, `estimated_heap ≤ 64 MiB/slab` (два pipeline-окна),
`durable_window ≤ 128 chunks`. Slab с oversized payload не запускает prefetch.

Chunk-readiness и lighting futures, а также backpressure лимита 128 активных chunk
writes используют остаток текущего 30-ms tick budget вместо безусловной потери
следующего 20-Hz poll. Если background work не завершился в budget, операция
продолжает обычный incremental fallback.

Перед разбиением на окна только уже доступные FULL chunks получают player-distance priority;
merely-updating holders остаются в region-local порядке холодного I/O. Entity chunks
детерминированно группируются по ближайшему к игроку region и локальному индексу файла,
чтобы последовательные batches переиспользовали один entity region без изменения durability-границ.

Оценка памяти консервативно суммирует raw section, native section, prepared delta, transient preparation и NBT expansion. Это инженерная оценка, не жёсткий heap cap: одновременно существуют JVM objects, futures, Minecraft state и GC-reclaimable allocation.

Каждое окно проходит apply → exact verify → staged persist и передаёт изменённые POI
в vanilla storage без physical force. После синхронного принятия immutable chunk/entity
snapshot его FULL ticket уже может быть освобождён; force/sync и persisted reread при этом
не считаются завершёнными. В entity-фазе освобождённые ticket-слоты могут загружать только
следующий 32-chunk batch; его mutation и persistence начинаются лишь после durability
текущего batch. Lookahead заполняется инкрементально только внутри текущего tick deadline;
сумма текущих и lookahead tickets никогда не превышает 32.
Финальный barrier один раз ждёт накопленные chunk/POI/entity writes, на том же
последовательном `IOWorker` форсирует затронутые region-файлы и выполняет reread всего
target. После всех окон завершается lighting; затем journal
получает `WORLD_PERSISTED`, и только после этого публикуется ref/pointer. Основной автомат
[RestoreOperation](../src/main/java/io/github/lumi/minecraft/operation/RestoreOperation.java):

`PREPARED → APPLYING → VERIFYING → PERSISTING → WORLD_PERSISTED → REF_PUBLISHED → COMPLETE`.

Ошибка до publication переводит операцию на подготовленную обратную сторону:

`ROLLING_BACK → apply(return) → verify(return) → persist(return) → COMPLETE`.

Если return не удаётся доказать, journal переводится в `DEGRADED`, freeze не снимается. Это fail-closed поведение предотвращает продолжение симуляции из недоказанного состояния.

### 5.3. Merge

[CommitGraph](../src/main/java/io/github/lumi/domain/service/CommitGraph.java) выполняет BFS от обоих heads и выбирает общего предка с минимумом `d1+d2`, затем `max(d1,d2)`, затем lexical commit ID. Стоимость `O(V+E)` по достижимому commit graph и `O(V)` memory.

[ThreeWayMerge](../src/main/java/io/github/lumi/domain/service/ThreeWayMerge.java) применяет к cell/entity/spawn правило:

`merge(b,c,s) = c`, если `c=s` или `s=b`;

`merge(b,c,s) = s`, если `c=b`;

`merge(b,c,s) = s` и `conflict+=1` во всех остальных случаях.

Следовательно, политика конфликта детерминирована как source-wins. Entity identity — UUID во всём наборе chunks, поэтому перемещение entity между chunks сравнивается как одно логическое изменение.

## 6. Надёжность и crash-consistency

Надёжность опубликованной истории обеспечивается сочетанием immutable objects/commits, forced temporary writes, atomic move, CAS refs, operation journal и retained return point. [AtomicFileWriter](../src/main/java/io/github/lumi/storage/repository/AtomicFileWriter.java) требует поддержку atomic move, выполняет `FileChannel.force(true)`, повторяет Windows `AccessDeniedException` не более пяти раз и проверяет прочитанные bytes после публикации.

| Момент отказа | Durable-наблюдение после restart | Действие |
| --- | --- | --- |
| Save до pack index/ref | старый ref валиден; новые данные недостижимы | orphan удаляется GC после 24 h |
| Save после journal, до ref | journal содержит expected ref и target commit | recovery валидирует commit и завершает CAS |
| Save после ref, до clear | ref уже доказывает публикацию | recovery признаёт CAS и очищает только captured generations |
| Restore до journal | world mutation ещё не началась | operation можно отменить |
| Restore в APPLYING/VERIFYING/PERSISTING | vanilla files могут быть смесью checkpoint/target | startup удерживает freeze; пользователь выбирает resume или return |
| Restore после `WORLD_PERSISTED`, до pointer publication | target физически проверен, pointer ещё не обязательно опубликован | journal остаётся authority; auto-finalize только если pointers уже доказывают publication |
| Restore после pointer publication, до journal clear | pointers доказывают завершение | idempotent startup finalization |
| Ошибка target и return | endpoint не доказан | `DEGRADED`, freeze и journal сохраняются |

Ограничение гарантии: persistence использует vanilla chunk/POI/entity/player storage. Lumi выполняет force и reread, но для player file не заявляет более сильную power-loss гарантию, чем vanilla process-crash boundary. Crash во время slabbed Restore может оставить обычные vanilla files в смешанном состоянии; journal делает его восстанавливаемым при наличии Lumi, но удаление мода до recovery не превращает смесь в атомарный endpoint.

GC строит корни из refs, tombstones, explicit retained commits, свежих commits и всех origins, затем трассирует commit parents и world-object graph. После удаления старых недостижимых данных та же операция crash-safe упаковывает не более одной группы world objects, достижимых от durable roots; свежая недостижимая history и raw orphans остаются отдельно удаляемыми. Стоимость полной сборки линейна по достижимому commit/object graph плюс полный inventory store; scheduler запускает её off-thread только при idle, впервые через 6000 ticks, затем раз в 72000 ticks, при busy повторяет проверку через 1200 ticks.

Проверки кода включают unit-тесты Save journal recovery, published apply recovery,
generation-safe clear, target/return/degraded Restore, object corruption и pack publication.
Интегрированный [LumiRecoveryClientGameTest](../src/gametest/java/io/github/lumi/gametest/LumiRecoveryClientGameTest.java)
проводит 40 loaded lectern POI через две 32-chunk durability windows, создаёт копию
durably restored world на crash boundary и проходит recovery UI;
[LumiWorldSnapshot](../src/gametest/java/io/github/lumi/gametest/LumiWorldSnapshot.java)
служит oracle блоков, block entities и entities.

### 6.1. Обязательная fault-injection матрица

Fault test должен завершать отдельный Minecraft/JVM process, а не только выбрасывать catchable exception. Для каждого cutpoint сохраняются exit code, последние durable logs, hashes repository/world до и после, journal/ref/pointer bytes и оба post-restart oracle.
Restore persistence предоставляет opt-in process cutpoints после первого staged write, write barrier, affected-region force и exact persisted verification. Без явных `lumi.gametest.restoreCrash*` JVM properties этот код выполняет только одно сравнение строки и не меняет production flow.

| Операция | Обязательные cutpoints |
| --- | --- |
| Save | до/после origin force; working-index publication; pack force; pack move; pack verification; index move; commit force; journal create; ref CAS; generation clear; journal clear |
| full Restore | journal create; каждый slab apply; verify; repair; staged write; force; persisted reread; lighting; `WORLD_PERSISTED`; ref publication; journal clear |
| branch/workspace switch | target world persisted; destination ref CAS; active-branch pointer; active-workspace pointer; captured-generation clear |
| partial/zone Restore | checkpoint publication; scoped apply; zone-revision validation; pending-state publication; journal clear |
| Quick Restore/checkpoint Undo | hidden checkpoint ref; target/return apply; inverse working-index publication; session-ref release |
| Merge | merge commit publication; target apply/persist; two-parent ref publication; safe return |

Минимальный post-crash oracle:

1. Все pre-test visible refs, commits и reachable objects читаются и сохраняют hashes.
2. Startup либо автоматически финализирует уже доказанную publication, либо удерживает freeze и предлагает только `RESUME_TARGET`/`RETURN_CHECKPOINT`.
3. После выбранного направления `W` точно равен соответствующему oracle, а unrelated state не изменён.
4. Working/builder generations совпадают с направлением и сохраняют `g'>captured(g)`.
5. Второй restart ничего не применяет повторно, journal/temporary refs очищены согласно terminal state.
6. Один и тот же cutpoint проверяется для loaded, stored и mixed path; state coverage включает block entity NBT, entity create/remove/move и respawn point там, где операция их поддерживает.

Power-loss/faulty-filesystem исследование является отдельным уровнем: process kill проверяет protocol ordering при соблюдении `force`/atomic move, но не моделирует накопитель, нарушающий эти контракты.

## 7. Ограничения конкурентности и памяти

| Ресурс | Реальный предел |
| --- | ---: |
| активные mutating operations на dimension | 1 |
| очередь dimension operations | 64 |
| operation background pool | 2 threads, queue 256 |
| durability background pool | 2 threads, queue 256 |
| server-tick work budget coordinator | 50,000,000 ns |
| Restore slab | два pipeline-окна по estimate 64 MiB и максимум 1024 sections каждое; oversized без prefetch |
| chunk window / simultaneous vanilla writes | 128 |
| pending FULL load activation / legacy entity batch | 32 |
| decoded section LRU | 32 на каждое направление |
| pending preview block positions | 16,384 |
| working-index keys | 1,000,000 |
| checkpoint refs общего retention-класса | 16 |

50 ms является deadline для кооперативных cursors, а не preemptive hard limit: один блокирующий вызов способен завершиться позже deadline. Поэтому фактический max complete server tick измеряется отдельно и является более сильным критерием, чем наличие budget в коде.

Idle tick вызывает coordinator, zone-growth flush, раз в 20 ticks retry durability, optional auto-version check и GC scheduler check. При выключенных auto versions и отсутствии работы эти пути состоят главным образом из проверок флагов/счётчиков; однако в текущем дереве отсутствует отдельный vanilla/Lumi idle A/B gate. Следовательно, «statistically indistinguishable from vanilla» остаётся release-требованием, а не доказанным результатом этой ревизии.

## 8. Формальные метрики эксперимента

Для одного Restore harness измеряет:

`T_ui = t_enqueue - t_ui_start`,

`t_ui_start` фиксируется после установки независимого server tick probe и непосредственно перед Enter по уже сфокусированной кнопке подтверждения; служебная синхронизация и навигация harness не входят в UI latency.

`T_op = t_terminal - t_enqueue`,

`T_app = T_loaded_apply + T_lighting + T_storage_write + T_storage_barrier + T_storage_force`,

`M_extra = max_t(used_heap(t)) - used_heap_before`,

`T_tick_max = max_j(tick_end_j - tick_start_j)`.

`T_app` намеренно не включает chunk loading, storage read/patch и persisted verification; поэтому его нельзя интерпретировать как полную задержку. Отдельно регистрируются `chunkLoad`, `storageRead`, `storageWrite`, `storageSync`, `verification`, loaded/stored chunks, fallbacks, section swaps, changed blocks и packet bytes. Реализации: [LumiRestoreMeasurement](../src/gametest/java/io/github/lumi/gametest/LumiRestoreMeasurement.java), [LumiServerTickProbe](../src/gametest/java/io/github/lumi/gametest/LumiServerTickProbe.java).

Gate использует upper median `sorted[n/2]`, максимум по `T_op`, максимум `M_extra` и максимум полного server tick. Общие пределы: `M_extra ≤ 1 GiB`, `T_tick_max ≤ 50 ms`. Для `512×512×16`: median `T_app ≤ 750 ms`, maximum `T_op ≤ 3000 ms`; для `1000×1000×16`: median `T_app ≤ 2 s`; для `5000×5000×16`: maximum `T_op ≤ 60 s`.

Дополнительные показатели исследования:

`throughput = N_changed_blocks / T_op`,

`storage_density = Δrepository_bytes / N_unique_changed_blocks`,

`relative_change = (metric_candidate - metric_baseline) / metric_baseline`,

`exact = 1`, только если persisted reread и `LumiWorldSnapshot` совпали с целевым endpoint.

Для `n` независимых испытаний без отказа rule-of-three даёт лишь верхнюю 95%-границу вероятности отказа `p < 3/n`; детерминированные unit-тесты не являются независимой случайной выборкой, поэтому эту оценку допустимо применять только к повторным crash/behavior runs с заранее определённым распределением сценариев.

### 8.1. Статистический план

До запуска фиксируются primary metrics, practically significant effect и правила исключения samples. Не допускается выбирать лучший run, менять gate после наблюдения результата или смешивать cold/warm paths.

- Baseline и candidate запускаются в отдельных JVM на одинаковом world image, seed и endpoint sequence; порядок пары чередуется, чтобы уменьшить временной drift среды.
- Pilot оценивает дисперсию; окончательное `n` определяется power analysis для заданного эффекта, а не произвольным числом повторов.
- Для latency публикуются все samples, median, nearest-rank p95/p99, maximum и 95% bootstrap confidence interval. Average допускается только вместе с распределением.
- Для paired A/B primary effect равен `d_i = candidate_i - baseline_i`; публикуются median `d`, относительное изменение и confidence interval.
- Для heap одновременно сохраняются used heap time series, GC events, allocation rate и post-GC live set. `M_extra` без GC-контекста обозначает occupancy, не retained memory.
- Для idle используется equivalence test с заранее заданными симметричными margins по tick time, CPU и allocation. Неспособность отвергнуть обычную null hypothesis различия не является доказательством эквивалентности.
- Любое нарушение exact oracle, crash invariant или 50 ms maximum tick является отдельным failure и не усредняется с успешными runs.

Интегрированный client/server benchmark содержит UI scheduling и совместный JVM noise. Для локализации причин нужны оба уровня: end-to-end `T_ui+T_op` и instrumented phase timings. Phase timings объясняют результат, но release gate применяется к end-to-end операции.

## 9. Зафиксированные экспериментальные результаты

Исторические результаты ниже взяты из [behavior-test-findings.md](behavior-test-findings.md); они не заменяют повторный benchmark текущего бинарного артефакта.

| Профиль | Результат | Интерпретация |
| --- | --- | --- |
| `512×512×16`, 4,194,304 random blocks, 2026-07-21 | base Save 6.988 s, рост 7,920,297 B (`1.89 B/block`) | измеренная плотность для конкретной random palette |
| изменение `256×256×16`, 1,048,576 assignments | Save 1.938 s, рост 2,135,064 B (`2.04 B/block`) | delta Save зависит от изменённых sections, не полного мира |
| 196 dense Saves | median 1.961 s; first→last quarter median `+25%`; repository 396,090,122 B | выявлена деградация queue/publication при росте history |
| latest natural branch-switch A/B, 2026-07-28 | median-of-run-medians 49.117→9.937 s; cold 230.303→10.488 s; `T_app` 3.340→3.317 s; max tick 13 ms | крупное улучшение loading/preparation, application почти неизменно |
| latest stored A/B | cold total 8.334→5.983 s; cold app 1.494→1.456 s; mixed median app 1.538→1.810 s | stored non-regression не доказан |
| candidate, heap 4 GiB | `M_extra=1.38–1.50 GiB` | формальный 1 GiB gate не пройден |
| candidate, heap 2 GiB | `M_extra=674,183,888 B`, max tick 13 ms, median Restore 9.894 s | bounded progress подтверждён под memory pressure, но это отдельный run |
| real player world, 31 versions | Restore 48.021/10.883/18.556 s; chunk load 41.541/7.936/14.688 s; max tick 35/21/5 ms | latency доминируется readiness chunks |
| heavy real endpoint, 940+940 sections | preparation 5.258 s; total 108.758 s; chunk load 96.651 s; live apply 157 ms; max tick 46 ms | два bottleneck: immutable decode и последовательная readiness |
| fresh-JVM cold Merkle-read A/B, 2026-07-30 | median diff 2.882→2.482 s (`-13.9%`); median total 11.231→10.265 s (`-8.6%`); candidate heap 134,870,016–235,813,672 B | baseline содержит пять runs, candidate — три; все candidate runs восстановили точный digest |
| existing-world branch switch, 2026-08-02 | baseline click→complete 5.853/5.214/6.065 s, median 5.853 s; 128-window candidate 5.095/4.257/4.104 s, median 4.257 s | одинаковые endpoints и isolated world copy; 256-window median 4.505 s и 50-ms budget median 4.359 s были хуже; три samples — engineering evidence, не release statistics |
| final scoped-force confirmation, 2026-08-02 | click→complete 6.188/5.005/5.101 s, median 5.101 s; median preparation 620 ms, chunk readiness 1.626 s, application 1.034 s, persisted verification 577 ms | report `20260802-101411-history-benchmark-existing-world-branch-switch`; exact reopen, entity UUID and runtime-health gates passed, but the 1.5–2.2 s target was not met and the earlier 4.257 s candidate was not reproduced by the final implementation |
| completed-plan rerun, 2026-08-02 | click→complete 5.711/4.602/4.806 s, median 4.806 s; median preparation 607 ms, chunk readiness 1.753 s, application 961 ms, write 497 ms, force 32 ms, persisted verification 571 ms | report `20260802-131943-history-benchmark-existing-world-branch-switch`; exact reopen, entity UUID and runtime-health gates passed. During the 18-tick intent window the live world changed by 2–9 sections and 14–25 entity chunks, so exact tree revalidation correctly selected the cold fallback. Relative to the final 5.101 s confirmation this is `1.06×`, not the target `3–4×` |

По последней зафиксированной исторической серии release-gate не пройден: natural result 9.937 s превышает 3 s, application около 3.317 s превышает 750 ms, а 4 GiB run превышает 1 GiB extra heap. Снижение требований не является техническим улучшением; измерения указывают на chunk readiness, vanilla write/sync и persisted verification как на доминирующие области, а не на direct section swap.

В candidate физически упорядоченный список `ChunkTree` делится на два непрерывных диапазона между текущим read session и одним независимым helper session. Порядок сохраняется внутри каждого диапазона. Количество helper tasks постоянно и не зависит от размера Restore; при отказе executor чтение остаётся последовательным.

### 9.1. Ограничение потенциального ускорения

Для latest natural sample устранение всей measured application оставило бы не менее:

`9.937 - 3.317 = 6.620 s`,

что всё ещё больше 3 s gate. Следовательно, только оптимизация apply/write/sync не может выполнить этот gate.

Для heavy real endpoint доля live mutation:

`f_apply = 0.157 / 108.758 ≈ 0.00144 = 0.144%`.

Даже нулевая стоимость live apply дала бы по закону Амдала ускорение не более `1/(1-f_apply) ≈ 1.00144`. Chunk readiness занимает `96.651/108.758 ≈ 88.87%`; этот профиль опровергает приоритет дальнейшей микрооптимизации section swap. Вывод относится к данному endpoint и требует повторения на ревизии исследования.

## 10. Оценка принятых решений

| Решение | Подтверждённое преимущество | Цена/остаточный риск | Оценка |
| --- | --- | --- | --- |
| content-addressed sparse Merkle | дедупликация; Compare/Restore зависят от endpoint delta, не длины history | canonical encode/hash/decode и catalog lookup дают CPU/I/O | соответствует задаче |
| immutable pack + отдельно опубликованный index | crash до visibility не повреждает старую историю; один file вместо file-per-object | orphan pack до GC; partially-live pack нельзя уменьшить | оправдано |
| origin + generation working index до vanilla publication | сохраняет pre-mutation endpoint и не теряет late mutation | background writes, tickets и save gate добавляют сложность | критично для надёжности |
| CAS refs/pointers | stale client/operation не переписывает новый HEAD | требует recovery для crash между world и pointer | оправдано |
| двунаправленный Restore plan + journal | заранее подготовлен проверяемый return path после apply/verify/persist failure | return также может отказать и оставить `DEGRADED`; память/подготовка примерно для двух направлений | надёжность приоритетнее latency |
| 128-chunk write windows, slab durability | сокращает число load/write/readback волн; 256-window в A/B ухудшил sync и медиану | force и persisted reread остаются на границе slab | лучший из измеренных вариантов 32/128/256 |
| два estimated 64 MiB slab | перекрывают decode с apply/persist в прежнем суммарном бюджете | estimate не является верхней границей JVM heap; oversized не prefetchится | требует heap-метрики, не только estimate |
| direct native section replacement | direct apply измеряется десятками/сотнями ms даже на больших endpoint | общая операция всё ещё ограничена loading/persistence | решение эффективно локально |
| vanilla storage, без собственного world overlay | удаление Lumi оставляет обычный мир; неизвестные vanilla данные сохраняются | whole-world publication не атомарна, recovery зависит от journal | соответствует product constraint |
| source-wins merge | детерминирован и прост для пользователя | конфликт не разрешается семантически и может скрыть current change | допустимо только при явном preview/confirmation |
| два изолированных bounded pools | durability не конкурирует с operation queue; нет unbounded executor | saturation вызывает rejection и terminal failure/retry | корректная backpressure |
| крупный `FabricDimensionRuntime` | все lifecycle invariants собраны в одном composition root | 2011 строк и много причин изменения | главный архитектурный риск сопровождения |

## 11. Воспроизводимый протокол дальнейшего исследования

### 11.1. Preregistration

До получения результатов создаётся immutable protocol record со следующими полями:

`{code_commit, artifact_sha256, hypotheses, primary_metrics, profiles, seeds, sample_size_rule, gates, equivalence_margins, exclusion_rules, analysis_version}`.

Изменение protocol после первого sample создаёт новую experiment series; старые данные не смешиваются с новой серией. Для каждого run manifest должен содержать:

`{run_id, UTC time, OS/build, CPU, RAM, storage model/filesystem, power mode, JDK, JVM args, heap, Minecraft/Fabric/mod versions, world_sha256, seed, path, warm/cold state, raw_report_sha256, exit status}`.

### 11.2. Последовательность эксперимента

1. Зафиксировать commit, JDK, Minecraft/Fabric versions, JVM flags, heap, CPU, storage device, ОС, render/simulation distance и набор модов.
2. Отключить Save previews для storage/performance профиля; отдельно измерять их стоимость, не смешивая с core history.
3. Использовать одинаковый seed (`710` уже является default harness), одинаковые endpoints и отдельный новый JVM для каждого baseline/candidate run.
4. Разделять resident, natural cold и forced stored path. После каждого Restore проверять exact snapshot, persisted reread, reopen и неизменность исходной копии мира.
5. Для latency публиковать `T_ui`, `T_op`, все phase timings, median, p95, maximum и число samples; для A/B — paired difference по одинаковым endpoints.
6. Для памяти снимать used heap достаточно часто, фиксировать GC events и повторять с несколькими heap caps; `M_extra` без GC-контекста является occupancy, а не retained size.
7. Для tick-cost использовать полные START/END server ticks; отдельно считать p50/p95/p99/max и число превышений 50 ms.
8. Для history scaling держать размер delta постоянным, варьировать число commits и отдельно измерять queue wait, pack publication, ref publication и repository inventory scan.
9. Для crash-consistency инъецировать остановку в каждой durable-фазе Save и Restore, затем проверять оба разрешённых направления, pointers, journal cleanup, working generations и повторный reopen.
10. Для idle сравнивать vanilla и Lumi на одинаковых сохранениях несколькими JVM runs; заранее задать equivalence margin для tick time, allocation и chunk-load latency. Без такого теста формулировка «неотличимо от vanilla» не доказана.

Harness уже поддерживает opt-in dense fixture, natural/stored modes, branch-switch, real existing-world copy, per-phase telemetry и performance gate: [LumiHistoryBenchmarkConfig](../src/gametest/java/io/github/lumi/gametest/LumiHistoryBenchmarkConfig.java), [LumiHistoryBenchmarkScenario](../src/gametest/java/io/github/lumi/gametest/LumiHistoryBenchmarkScenario.java), [LumiRestorePerformanceGate](../src/gametest/java/io/github/lumi/gametest/LumiRestorePerformanceGate.java). Existing-world branch-switch создаёт две ветки на явно заданных endpoint-коммитах только внутри одноразовой копии, переоткрывает её и измеряет настоящий cold/warm цикл через branch hotkeys.

### 11.3. Факторная матрица

| Фактор | Минимальные уровни | Изолируемый эффект |
| --- | --- | --- |
| history length `h` | 1, 10, 100, 1000 при одинаковых endpoints | зависимость от числа commits/catalog |
| changed sections `SΔ` | 0, 1, 1024, 1025 | slab boundary и linear decode |
| changed chunks `CΔ` | 1, 32, 33, 1024 | ticket/write window и force barriers |
| density | 1 block/section, sparse, все 4096 cells | scan, palette и packet cost |
| locality | один chunk; один region; region-scattered | chunk readiness и storage synchronization |
| chunk path | resident, natural cold, forced stored, mixed | loading против storage |
| state kind | blocks; block entities с NBT; entity create/remove/move; spawns | отдельные persistence paths |
| history operation | Save, full/partial/zone Restore, branch switch, Merge, Quick Restore | protocol-specific cost и correctness |
| heap | не менее 2 GiB и 4 GiB | GC pressure и bounded progress |
| storage | минимум один SSD-профиль; дополнительные устройства отдельно | внешняя валидность sync/readiness |

Нулевой delta обязателен: он отделяет fixed orchestration/return-point cost от работы, пропорциональной изменениям. Значения 32/33 chunks и 1024/1025 sections проверяют реальные границы реализации. Synthetic dense fixture и реальные миры анализируются отдельно.

### 11.4. Контракт артефактов

Каждая experiment series должна сохранять:

- clean source commit и собранный mod JAR с SHA-256;
- неизменяемый исходный world image и hash inventory;
- manifest среды и preregistration;
- stdout/stderr и Lumi/Minecraft logs без ручного редактирования;
- raw JSONL events, tick samples, heap/GC series и repository-size inventory;
- pre/post/reopen world oracle hashes;
- fault cutpoint и durable files для crash runs;
- analysis script/notebook с pinned dependencies и generated tables/plots;
- итоговый machine-readable verdict по каждому `C1`–`C8` и `I1`–`I10`.

Raw data не хранится только внутри `build/`, если этот каталог не входит в архив исследования. Документ не должен ссылаться на локальный report, отсутствующий в опубликованном artifact bundle.

### 11.5. Критерии завершения

| Область | Условие PASS |
| --- | --- |
| correctness | ноль oracle mismatches во всех обязательных workflow/path/state combinations |
| crash safety | каждый cutpoint приводит только к доказанному target, доказанному return или frozen recoverable state; старые published roots неизменны |
| idempotence | два последовательных reopen после recovery не меняют world, refs, generations или journal |
| performance | все существующие `LumiRestorePerformanceGate` budgets выполнены без изменения gate |
| memory | gate 1 GiB выполнен, post-GC live set не масштабируется с полным operation volume при фиксированном slab |
| tick | ни один complete server tick обязательного профиля не превышает 50 ms |
| idle | equivalence test проходит preregistered margins; до задания margins статус остаётся `NOT TESTED` |
| reproducibility | независимый rerun из artifact bundle воспроизводит verdict и confidence intervals |

Failure correctness/crash/idempotence блокирует release независимо от performance. Performance failure не разрешается ослаблением integrity, force, persisted reread, journal или CAS.

## 12. Угрозы валидности

- `Construct validity`: owned-state oracle не охватывает биомы, scheduled ticks, scoreboard, weather и иные неверсируемые подсистемы; результат нельзя называть полным snapshot Minecraft.
- `Internal validity`: JIT warm-up, GC, OS page cache, antivirus, background I/O, совместный client/server JVM и UI scheduling способны менять latency. Они фиксируются или рандомизируются, но не объясняются задним числом.
- `External validity`: synthetic random palette не представляет все реальные NBT/entity workloads; один CPU, filesystem или world не обобщается на все установки.
- `Conclusion validity`: maximum нестабилен, малое `n` не даёт надёжных tail estimates, а отсутствие наблюдаемого crash failure не доказывает его невозможность.
- `Instrumentation`: sampling used heap может пропустить краткий peak; phase timers могут перекрываться или исключать ожидание. End-to-end clock и independent complete-tick probe остаются primary.
- `Artifact drift`: результаты другой ревизии, dirty worktree или отсутствующий raw report не подтверждают текущий код.
- `Model limitation`: atomic move и `force` принимаются согласно контракту ОС/filesystem; power-loss поведение неисправного накопителя требует отдельной fault model.

## 13. Итог

Архитектура Lumi согласована с задачей безопасной истории строительных изменений: published history immutable, stale publication блокируется CAS, live mutations получают durable origins до vanilla save, Restore публикует указатели только после apply/verify/persist/reread, а недоказанное состояние не выпускается из freeze. Математическая стоимость Save и endpoint Compare определяется разреженной областью изменений; стоимость block-exact Restore дополнительно линейна по числу декодируемых секций и их 4096 cells.

Надёжность старой истории обоснована кодом и unit/integration tests сильнее, чем скорость крупного Restore, но ещё не имеет phase-exhaustive process-crash доказательства. Доступные исторические данные не позволяют объявить release readiness: строгие application/full-time/4-GiB-memory gates не выполнены, статистический idle A/B gate отсутствует, а результаты не воспроизведены для зафиксированной ревизии `9011a1de`. Следующий технический выбор должен оцениваться по уменьшению chunk-readiness и durable sync/reread latency без удаления journal, persisted verification, CAS или bounded-memory ограничений.

## 14. Исправление first-touch Save

Инцидент 2026-07-31 показал Save длительностью 68,182 s. Из них 66,276 s заняло ожидание durable origins для 1 304 ключей. Capture занял 486 ms. Причиной был не объём области Save, а последовательная схема `object + .origin` с отдельным forced atomic file для каждого впервые изменённого ключа. Существующий player-scale benchmark не обнаруживал дефект, потому что явно завершал durability до запуска таймера Save.

Принято минимальное изменение persistence:

- captured origins идут в существующий immutable object `WriteBatch` не более чем по 256 ключей;
- соответствия `HistoryKey -> ObjectId` публикуются отсортированными атомарно заменяемыми shards по Merkle-region 32×32 chunks;
- legacy `LOR2` files остаются читаемыми и участвуют в conflict check;
- working index и vanilla chunk publication остаются заблокированными до успешной публикации всех shards текущей пачки;
- сбой после object pack и до последнего shard оставляет безопасный orphan/partial durable state; retry использует тот же captured origin и не выполняет повторный capture.

Player-scale scenario теперь запускает каждый из 31 Save без предварительного durability drain. Он пишет `save_metrics` с числом pending keys и применяет неизменяемый gate 6 800 ms к полному UI Save. Unit regression использует 257 разных origins, подтверждает две bounded object batches и повтор после отказа между pack и shard без второго pack или повторного capture.

Первый полный прогон выявил отдельную ошибку согласованности: долгоживущий `OriginStore` мог сохранить пустой shard в памяти и не увидеть его публикацию другим экземпляром repository. Кэш теперь обновляется при изменении размера append-only shard. Regression проверяет оба направления: чтение нового shard после пустого результата и чтение дополненного shard.

Три последовательных Save-only прогона в новых JVM дали 93 измерения без отказов:

| Run | Initial, ms | Median, ms | P95, ms | Maximum, ms | Save 30, ms | Maximum origin wait, ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `20260731-044453` | 1 968 | 944 | 1 968 | 2 842 | 2 842 | 348 |
| `20260731-044835` | 1 635 | 897 | 1 805 | 3 370 | 3 370 | 300 |
| `20260731-045156` | 1 197 | 941 | 1 781 | 2 823 | 2 823 | 450 |

Объединённая медиана равна 926 ms, P95 — 1 807 ms, максимум — 3 370 ms. Худший результат в 20,23 раза быстрее исходных 68 182 ms и проходит gate 6 800 ms с запасом. Эти прогоны изолируют Save; correctness Restore и его performance gates проверяются отдельными сценариями.

## 15. Минимальный crash-journal Restore

Restore сохраняет только durable-переходы, необходимые для выбора recovery. Перед первой
мутацией создаётся `PREPARED`, а перед применением он переводится в `APPLYING`. После этого
journal остаётся в `APPLYING`, пока проверенный и принудительно сохранённый мир не опубликован
через CAS либо пока не начат безопасный rollback. Промежуточные записи `VERIFYING`,
`WORLD_PERSISTED`, `REF_PUBLISHED` и `COMPLETE` не нужны для корректности: без опубликованного
указателя recovery может идемпотентно повторить target или return, а опубликованный указатель
уже распознаётся `PublishedApplyRecovery`. Успешная операция удаляет исходный journal напрямую.

Это не ослабляет apply/verify/persist/reread, CAS или exact reopen. Из критического пути
удалены только повторные forced atomic rewrites одного и того же recovery-решения.

Подготовка hidden return-point устанавливает точную generation boundary, но не ждёт старые
origin/index writes перед capture. Это допустимо только для return-point: до первой мутации
его полный checkpoint и ref становятся durable, после чего journal ссылается на этот commit
как на точное направление rollback. Обычный Save по-прежнему ждёт origin/index durability,
а vanilla publication остаётся закрытой существующим durability gate.

Process-level проверка Restore запускается задачей `runRestoreCrashMatrix`. Для фаз
`write`, `barrier`, `force` и `verify` отдельный producer создаёт настоящий мир, начинает
Restore и завершает JVM через `Runtime.halt` только после durable marker. Новый JVM открывает
тот же мир, подтверждает frozen recovery, завершает target, проверяет блоки, UUID сущности,
branch ref, revision и удаление journal, затем выполняет ещё один чистый exact reopen.
Полная матрица из восьми producer/verifier задач прошла за 6 min 11 s. Дополнительно прошли
850 unit tests, интегрированный zone/entity exact Restore и existing-world branch-switch с
persisted UUID audit и чистым reopen.
