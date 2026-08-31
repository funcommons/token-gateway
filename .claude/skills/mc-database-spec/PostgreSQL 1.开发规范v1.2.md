# **PostgreSQL 开发规范 1.2**

本文档旨在建立一套适用于PostgreSQL数据库的研发规范，结合阿里巴巴Java开发规约的指导思想，以提升团队协作效率、保障数据安全及系统性能。

## **一、表设计规范**

### **【强制】**

1. **主键id**  
   * 表必须有主键, 统一命名id  
   * bigint类型，或bigserial（自增bigint，8字节）或 serial（4字节，仅在字典表中使用）。  
   * 实体表 事务表  属性表 继承表  必须用bigint类型（雪花算法ID）  
   * 关系表 属性表 日志表 汇总表 bigserial  
   * 字典表 bigserial/serial  
2. **字段** created\_at  
   * created\_at：创建时间，类型为 timestamp with time zone (timestamptz)，默认为 CURRENT\_TIMESTAMP。  
   * 所有表必备  
3. **字段** updated\_at  
   * updated\_at：更新时间，类型为 timestamp with time zone (timestamptz)，默认为 CURRENT\_TIMESTAMP。  
   * 用触发器在 UPDATE 时自动更新此字段。  
   * 除日志表, 关系表外其它表必备,   
4. **字段** is\_deleted  
   * 说明：实体表 事务表 及对应属性表(不含EAV) 必须有逻辑删除字段，is\_deleted，类型为 smallint（0=未删除, 1=已删除），默认为 0。禁止物理删除。  
   * 实体表 事务表 必备  
5. **库、表、字段命名规范**  
   * 说明：必须使用小写字母、数字、下划线（\_）的组合。禁止使用CamelCase（驼峰命名）。表名不应超过32个字符，字段名不应超过32个字符。命名必须见名知意。  
6. **字段必须为 NOT NULL 并指定 DEFAULT 值**  
   * 说明：除极少数业务上允许NULL的字段外（如 profile 中的 bio），所有字段必须声明为 NOT NULL，并提供 DEFAULT 值。  
   * 理由：NULL 会导致 IS NULL / IS NOT NULL 的索引优化困难，并在 count() 等聚合函数中引发非预期行为。  
7. **禁止使用外键约束**  
   * 说明：禁止在表定义中使用 FOREIGN KEY 约束。数据一致性、完整性应在应用层保证。  
   * 理由：外键会严重影响高并发下的 INSERT/UPDATE 性能，增加数据库锁开销，并在分库分表时带来巨大障碍。  
8. **varchar 必须指定长度**  
   * 说明：虽然 varchar(n) 和 text (或 varchar) 在 PG 中性能差异不大，但 varchar(n) 提供了数据层面的约束，能防止应用层异常数据（如超长字符串）入库。  
9. **禁止使用 ENUM 类型**  
   * 说明：禁止使用 PostgreSQL 的 ENUM 类型。对于状态、类型等字段，必须使用 varchar(n)，值统一采用大写英文（如 SUCCESS、FAIL、PENDING），并通过应用层限制取值范围。
   * 理由：ENUM 类型不利于扩展（修改需要 ALTER TYPE，可能锁表），且不利于跨数据库迁移。

### **【推荐】**

9. **表名应体现业务模块**  
   * 说明：推荐使用 业务模块\_表名 的方式命名，如 user\_account, trade\_order, trade\_order\_detail。  
10. **选择最合适的数据类型**  
    * 说明：选择能满足业务最小需求的类型。  
    * 金额/货币：必须使用 numeric(M, D)，禁止使用 float / double。  
    * 时间日期：必须使用 timestamp with time zone (timestamptz)，避免时区问题。  
    * IP地址：推荐使用 inet 类型。  
    * JSON数据：必须使用 jsonb 类型（而非 json），并配合 GIN 索引。  
    * 状态/类型：必须使用 varchar(n) 类型，值必须为大写英文字符串（如 SUCCESS、FAIL、PENDING、ACTIVE、INACTIVE）。禁止使用 smallint/char(1)/数字编码表示状态。
11. **索引命名规范**  
    * 普通索引：idx\_字段1\_字段2 (e.g., idx\_user\_name)  
    * 唯一索引：uk\_字段1\_字段2 (e.g., uk\_user\_email)  
12. **表和字段必须添加注释**  
    * 说明：使用 COMMENT ON TABLE ... IS '...'; 和 COMMENT ON COLUMN ... IS '...'; 为所有表和字段添加清晰的中文注释，便于团队协作和维护。  
13. **适度进行垂直拆分（冷热分离）**  
    * 说明：将 text, bytea, jsonb 等大字段，或不常查询的字段，拆分到单独的扩展表中，通过主键 id 保持一对一关系。  
    * 理由：避免在查询主表（热数据）时，因大字段导致 IO 开销过大。  
14. **区分“代理主键”与“业务主键”**  
    * 说明：在设计表（特别是字典表、配置表）时，应同时使用代理主键和业务主键，它们承担不同职责。  
    * **1\. 代理主键 (Surrogate Key):** 应为 id (bigint/bigserial)，作为物理主键 (PK)。优点是数据库性能高、索引高效、且“永不改变”。  
    * **2\. 业务主键 (Business Key):** 应为 code (varchar / int) 等有业务含义的字段，设为 NOT NULL UNIQUE (唯一索引)。优点是业务可读性强，保证业务唯一性。  
    * **3\. 引用规范 (重要):** 当其他表（如 users 表）需要引用此表时，**推荐** 引用 code (业务主键)，而不是 id (代理主键)。  
      * **理由 (可读性):** 在查询时 country\_code \= '+086' 远比 country\_code\_id \= 123 更易于排查和维护。  
      * **理由 (可移植性):** code 在多套环境（开发、测试、生产）中保持一致，而 id (自增) 可能不同，导致数据迁移困难。  
      * **理由 (缓存):** 字典表应全量加载缓存，应用层通过 cache.getByCode("+086") 获取，不应依赖数据库 JOIN。  
      * **前提：** 此规范的前提是 code 字段一旦创建，即视为**不可变 (Immutable)**。

## **二、SQL 开发规范**

### **【强制】**

1. **所有SQL都必须携带业务追踪信息**  
   * 说明：所有由应用发出的SQL，必须通过框架（如MyBatis插件）自动添加注释，包含traceid和topic（项目名）。这对于线上问题排查、慢SQL溯源和统一运维至关重要。  
   * 正例：/\*traceid=abc123xyz,topic=MyProject\*/ SELECT id, name FROM users WHERE id \= ?;  
2. **查询必须包含LIMIT，单次返回不允许超过1000行**  
   * 说明：禁止返回海量数据给应用层。任何查询都必须有LIMIT限制；如果预估数据量超过1000行，应在业务层进行分页处理，多次查询。  
3. **禁止使用 SELECT \***  
   * 说明：必须明确写出所有需要查询的字段。SELECT \* 会增加网络开销，降低查询优化器效率，且在表结构变更时可能导致应用代码序列化失败。  
   * 正例：SELECT id, name, age FROM users WHERE ...  
   * 反例：SELECT \* FROM users WHERE ...  
4. **查询必须利用索引，避免全表扫描 (Seq Scan)**  
   * 说明：WHERE 子句中的条件，至少应有一个命中了索引。对于大型表（如超过100万行）的 Seq Scan (全表扫描) 是性能瓶颈。开发阶段必须使用 EXPLAIN 分析执行计划。  
5. **禁止 JOIN 超过三个表**  
   * 说明：需要 JOIN 的字段，数据类型必须绝对一致（包括长度、精度）；多表关联查询时，保证被关联的字段（ON 子句中的字段）必须有索引。  
6. **禁止 JOIN 子查询**  
   * 说明：禁止在 JOIN 子句中嵌套 SELECT 语句（例如 ... JOIN (SELECT ...) sub ON ...）。复杂的逻辑应在应用层进行数据组装，或者使用 WITH 子句 (CTE) 提高可读性。  
7. **严禁左模糊或全模糊搜索**  
   * 说明：WHERE name LIKE '%... 或 WHERE name LIKE '%...%' 会导致 B-Tree 索引失效。  
   * 正例：WHERE name LIKE '...%' (右模糊)  
   * 特例：如果业务上必须支持任意位置的模糊搜索，**必须** 使用 PostgreSQL 的 pg\_trgm 扩展并建立 GIN 索引，或使用全文搜索 (tsvector/tsquery)。  
8. **业务唯一字段必须建立唯一索引 (UNIQUE INDEX)**  
   * 说明：即使是多个字段的组合，只要业务上具有唯一性，就必须建立唯一索引。不要依赖应用层的逻辑校验，根据墨菲定律，只要没有唯一索引，就必然会产生脏数据。  
9. **【强制】使用“部分唯一索引”处理逻辑删除**  
   * **说明：** 这是为了解决【表设计规范 一、4】(is\_deleted) 与【SQL开发规范 二、8】(UNIQUE INDEX) 之间的冲突。标准的 UNIQUE 索引会检查所有行，导致逻辑删除（is\_deleted \= 1）的记录无法被重新注册。  
   * **解决方案：** 必须使用 PostgreSQL 的“部分索引” (Partial Index) 功能，仅对 is\_deleted \= 0 的行（即活动行）建立唯一索引。  
   * 正例 (Partial Unique Index):  
     \-- 仅在 "未删除" 的行中保证 email 唯一  
     CREATE UNIQUE INDEX uk\_users\_email\_active ON users (email) WHERE (is\_deleted \= 0);  
   * 反例 (Standard Unique Index):  
     \-- (此索引会导致逻辑删除后无法重新注册)  
     CREATE UNIQUE INDEX uk\_users\_email ON users (email);  
10. 理解 count(\*) 的性能，谨慎使用 (原第9条)  
    \* 说明：count(\*) 和 count(1) 都是统计总行数（包括NULL行），count(col) 统计该列非NULL的行数。在 PostgreSQL 中，count(\*) 需要进行全表扫描（或全索引扫描）以确保MVCC的可见性，在大表上性能较差。  
    \* 特例：如果业务可以接受近似值（非精确值），可以使用 SELECT reltuples::bigint FROM pg\_class WHERE relname \= 'your\_table\_name'; 来快速获取估算行数。  
11. 禁止使用外键与级联，一切外键约束在应用层解决 (原第10条)  
    \* 说明：外键与级联更新适用于单机低并发，不适合分布式、高并发集群；级联更新是强阻塞，存在数据库更新风暴的风险；外键影响数据库的插入和更新性能，并使得分库分表变得复杂。（重申表设计规范第6条）  
12. 禁止使用存储过程、函数、触发器 (原第11条)  
    \* 说明：业务逻辑应全部实现在应用层。存储过程、函数、触发器难以调试、扩展、移植和版本管理，将业务逻辑耦合在数据库中，不利于敏捷开发和后期维护。（例外：updated\_at 自动更新触发器可以使用）  
13. 禁止在应用代码中使用 TRUNCATE (原第12条)  
    \* 说明：TRUNCATE 会获取 ACCESS EXCLUSIVE 锁，阻塞表上的所有并发操作。虽然在 PG 中 TRUNCATE 是事务安全的，但其锁级别极高，极易引发线上阻塞。应在DBA运维窗口期使用，应用代码必须使用 DELETE。  
14. DELETE 和 UPDATE 必须在 WHERE 中指定主键或唯一键 (原第13条)  
    \* 说明：在数据订正或业务操作时，UPDATE 和 DELETE 必须携带 WHERE 条件，并优先使用主键或唯一索引作为条件，以避免误操作和长时间的锁等待。禁止无 WHERE 子句的 UPDATE / DELETE。  
15. SQL参数必须使用占位符 (?)，禁止字符串拼接 (原第14条)  
    \* 说明：字符串拼接是SQL注入的根源，是严重的安全漏洞。  
    \* 正例：jdbcTemplate.query("SELECT ... WHERE id \= ?", new Object\[\]{id}, ...);  
    \* 反例：jdbcTemplate.query("SELECT ... WHERE id \= " \+ id);  
16. 必须使用 Druid 连接池 (原第15条)  
    \* 说明：统一连接池技术栈，便于统一监控、运维和参数调优。Druid 提供了丰富的监控功能。  
17. 使用 EXPLAIN ANALYZE 分析性能 (原第16条)  
    \* 说明：任何复杂的查询或性能可疑的SQL，都必须在开发和测试环境中使用 EXPLAIN ANALYZE 进行分析。重点关注执行计划中的 Seq Scan、Sort、Bitmap Heap Scan 等高成本操作，并查看 actual time (实际执行时间)。  
18. 正确使用 JSONB 类型及其索引 (原第17条)  
    \* 说明：存储 JSON 数据时，必须使用 JSONB 类型（而非 JSON）。对 JSONB 列进行查询时，必须建立 GIN 索引 (e.g., CREATE INDEX ON table USING GIN (jsonb\_col))。必须使用 ?, ?|, ?&, @\> (包含) 等操作符来利用索引。

### **【推荐】**

\<\!-- ... 推荐部分内容不变 ... \--\>

1. **利用索引的有序性进行 ORDER BY**  
   * 说明：ORDER BY 字段应尽量是索引的一部分，并放在索引组合的末尾，以避免 Sort (文件排序) 操作。  
   * 正例：WHERE a \= ? AND b \= ? ORDER BY c; (索引 idx\_a\_b\_c)  
2. **利用覆盖索引 (Index Only Scan) 避免回表**  
   * 说明：查询的字段如果都能在索引中找到，PostgreSQL 会使用 Index Only Scan (仅索引扫描)，无需访问表数据（堆表），性能极高。  
   * 正例：SELECT b, c FROM table WHERE a \= ?; (索引 idx\_a\_b\_c)  
   * 技巧：PG 支持 INCLUDE 子句，允许在索引中“携带”非索引列，专用于覆盖索引。  
     CREATE INDEX idx\_a\_cover ON table (a) INCLUDE (b, c);  
3. **优化超多分页场景 (深分页)**  
   * 说明：OFFSET ... LIMIT ... 在 OFFSET 值非常大时，性能会急剧下降。  
   * 正例 (延迟关联/Seek法)：  
     SELECT a.\* FROM table1 a JOIN (SELECT id FROM table1 WHERE ... ORDER BY ... LIMIT 20 OFFSET 100000\) b ON a.id \= b.id;  
   * 更优 (游标法)：SELECT ... FROM table1 WHERE created\_at \> (last\_page\_last\_created\_at) ORDER BY created\_at LIMIT 20;  
4. **使用 COALESCE() 处理 sum() 的 NULL 问题**  
   * 说明：当 sum(col) 的结果集为空时，sum() 返回 NULL 而不是 0，可能导致应用层NPE (NullPointerException)。  
   * 正例：SELECT COALESCE(SUM(g), 0\) FROM table;  
5. **谨慎使用 IN 操作，使用 ANY 替代**  
   * 说明：IN 列表中的元素数量不应过多（建议控制在1000个以内）。对于大量元素，IN 性能不佳。  
   * 正例 (PG推荐)：使用数组和 ANY 操作符，性能更优。  
     ... WHERE id \= ANY(?); (JDBC中传递 java.sql.Array 对象)  
6. **使用 UTF-8 编码，区分 LENGTH 和 CHAR\_LENGTH**  
   * 说明：LENGTH() 返回字节长度，CHAR\_LENGTH() (或 CHARACTER\_LENGTH()) 返回字符长度。  
   * 正例：SELECT LENGTH('轻松工作'); (返回 12\)  
   * 正例：SELECT CHAR\_LENGTH('轻松工作'); (返回 4\)  
7. **分页查询时，若 count 为 0 应提前返回**  
   * 说明：在代码中执行分页逻辑时，应先执行 count 查询。如果 count \== 0，则应直接返回空列表，避免执行后续的 LIMIT 查询。  
8. **善用 WITH 子句 (CTE)**  
   * 说明：对于复杂的查询，使用 WITH 子句 (Common Table Expressions) 可以极大提高 SQL 的可读性和可维护性。PostgreSQL 的 CTE 优化非常出色。

## **三、反例分析**

以下是一条不规范的SQL示例：

select \* from fxa\_order\_all where status \= 9 and name like ’%有限公司’

不规范点分析：

1. **select \***：违反了【SQL开发规范-强制】第3条，应明确列出字段。  
2. **name like ’%有限公司’**：左模糊查询，违反了【SQL开发规范-强制】第7条，索引将失效，导致全表扫描。  
3. **缺少 LIMIT**：违反了【SQL开发规范-强制】第2条，可能返回大量数据。  
4. **缺少追踪信息**：违反了【SQL开发规范-强制】第1条，没有 /\*traceid...\*/ 注释。  
5. **status \= 9**：status 字段如果不是索引（或组合索引的前导列），也会增加扫描成本，违反【SQL开发规范-强制】第4条（假设该表很大）。