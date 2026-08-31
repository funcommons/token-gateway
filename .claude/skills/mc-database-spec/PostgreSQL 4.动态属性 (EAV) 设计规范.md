# **PostgreSQL 动态属性 (EAV) 设计规范**

## **1\. 目的与范围**

### **1.1. 目的**

本规范旨在为基于 PostgreSQL 数据库的应用提供一个统一、高性能、可维护的动态属性（EAV \- Entity-Attribute-Value）存储解决方案。目标是满足业务需求中对实体（如商品、用户、配置）的属性进行动态扩展，而无需频繁变更（ALTER TABLE）核心表结构。

### **1.2. 范围**

本规范适用于所有需要存储非结构化或半结构化动态键值对数据的业务场景。所有新项目的动态属性设计均应遵循此规范。

## **2\. 方案选型：JSONB**

### **2.1. 选定方案**

**核心方案：采用 PostgreSQL 原生的 JSONB 数据类型。**

所有动态属性应统一存储在实体主表的一个 JSONB 类型的列中。

### **2.2. 选型理由**

1. **查询性能高：** JSONB 是二进制格式，配合 GIN 索引，其键值查询、存在性检查、包含性检查（@\>）的性能远超传统 EAV 模型。  
2. **查询简洁性：** 避免了传统 EAV 模型所需的多表 JOIN 或 PIVOT 操作，SQL 语句更简单、更易维护。  
3. **数据灵活性：** 原生支持嵌套对象和数组，可以轻松存储如 tags: \["a", "b"\] 或 specs: {"cpu": "i7"} 这样的复杂结构。  
4. **事务原子性：** 实体的主属性和动态属性在同一行中，简化了事务和数据一致性管理。

### **2.3. 废弃方案：经典 EAV**

“经典 EAV” 方案（即创建 entities, attributes, values 三个独立表）由于以下原因**不被推荐**用于新项目：

* **查询复杂：** 检索多属性组合时需要多次 JOIN 或 GROUP BY，SQL 语句臃肿。  
* **性能瓶颈：** JOIN 操作在 values 表数据量巨大时性能急剧下降。  
* **数据类型管理困难：** 需要为不同数据类型创建不同 value\_xxx 列（如 value\_text, value\_numeric），增加了复杂性。

## **3\. 数据库架构设计**

### **3.1. 实体表 (Entity Table)**

实体表（例如 products）应包含所有静态、核心的列，并增加一个 JSONB 列用于存储所有动态属性。

* **命名规范：** JSONB 列应统一命名为 attributes。  
* **约束：** attributes 列必须为 NOT NULL，并建议设置一个空对象 '{}'::jsonb 作为默认值。

**DDL 示例 (以商品表为例):**

CREATE TABLE products (  
    id SERIAL PRIMARY KEY,  
    sku TEXT UNIQUE NOT NULL,      \-- 静态属性：SKU  
    name TEXT NOT NULL,            \-- 静态属性：名称  
    created\_at TIMESTAMPTZ DEFAULT NOW(),  
      
    \-- 动态属性列  
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb  
);

### **3.2. 属性定义表 (Attribute Definition Table)**

为了实现数据治理、输入校验和动态表单生成，**强烈建议**创建一个属性定义表来管理允许哪些动态属性。

* **命名规范：** 属性定义表应命名为 attribute\_definitions。

**DDL 示例:**

CREATE TABLE attribute\_definitions (  
    id SERIAL PRIMARY KEY,  
      
    \-- 机器可读的键名，与 JSONB 中的 key 对应  
    key\_name TEXT UNIQUE NOT NULL, \-- 例如: "weight\_kg", "color"  
      
    \-- UI 显示用的标签  
    label TEXT NOT NULL,           \-- 例如: "重量 (kg)", "颜色"  
      
    \-- 属性的数据类型，用于应用层校验  
    \-- 规范值: 'text', 'number', 'boolean', 'enum', 'array'  
    data\_type TEXT NOT NULL,  
      
    \-- 描述信息  
    description TEXT,  
      
    \-- 验证规则（可选）  
    \-- 例如: {"min": 0, "max": 100}, {"options": \["red", "blue", "green"\]}  
    validation\_rules JSONB  
);

## **4\. 索引策略**

### **4.1. GIN 索引 (强制)**

**必须**在 attributes 列上创建一个 GIN 索引，以加速 JSONB 的键值查询。

\-- 强制要求为所有 JSONB 动态属性列创建 GIN 索引  
CREATE INDEX idx\_products\_attributes\_gin ON products USING GIN (attributes);

此索引将高效支持以下操作符：

* @\> (包含)  
* ? (键是否存在)  
* ?| (是否存在任一键)  
* ?& (是否存在所有键)

### **4.2. B-Tree 表达式索引 (可选)**

如果业务场景中**极高频**地需要对 JSONB 内部**某个特定键**的值进行排序（ORDER BY）或范围查询（\>, \<,BETWEEN），可以考虑创建 B-Tree 表达式索引。

\-- 示例：如果需要频繁按重量(数字)查询和排序  
CREATE INDEX idx\_products\_attr\_weight   
ON products (((attributes-\>\>'weight\_kg')::numeric));

**注意：** 仅在确认 GIN 索引无法满足特定查询性能时才添加 B-Tree 索引，以避免过度索引。

## **5\. 数据模型与约束**

### **5.1. 数据结构**

attributes 列中存储的**必须**是一个 JSON **对象** (Object)，即 { "key": "value" } 结构。

### **5.2. 数据类型规范**

为了确保查询（尤其是范围查询）的正确性，JSONB 内部的值必须遵循以下类型规范：

* **字符串 (Text):** 必须存储为 JSON 字符串。  
  * {"color": "red"}  
* **数字 (Number):** 必须存储为 JSON 数字，**严禁**存为字符串。  
  * **正确:** {"weight\_kg": 0.25}  
  * **错误:** {"weight\_kg": "0.25"} (这将导致数值比较和排序失败)  
* **布尔值 (Boolean):** 必须存储为 JSON 布尔值。  
  * {"is\_on\_sale": true}  
* **枚举 (Enum):** 存储为 JSON 字符串，其允许的值由 attribute\_definitions.validation\_rules 定义。  
* **数组 (Array / Tags):** 必须存储为 JSON 数组。  
  * {"tags": \["new\_arrival", "sale"\]}  
* **空值:** 应使用 JSON null，或直接删除该键。

### **5.3. 约束与校验**

数据一致性主要由**应用层**负责。应用在执行 C(Create) 和 U(Update) 操作前，**必须**：

1. 查询 attribute\_definitions 表。  
2. 校验 key\_name 是否合法。  
3. 校验 value 是否符合 data\_type 和 validation\_rules 定义的规则。

## **6\. 标准查询模式 (CRUD)**

以下为推荐的 SQL 操作模式。

### **6.1. C (Create) \- 创建**

创建实体时，直接插入完整的 attributes JSONB 对象。

INSERT INTO products (sku, name, attributes)  
VALUES   
('TSHIRT-001', '红色T恤', '{"color": "red", "weight\_kg": 0.2, "tags": \["sale", "new"\]}');

### **6.2. R (Read) \- 查询**

#### **6.2.1. 按精确键值查询 (推荐)**

使用 @\> (contains) 操作符，它将高效利用 GIN 索引。

\-- 查询所有红色的商品  
SELECT \* FROM products  
WHERE attributes @\> '{"color": "red"}';

\-- 查询所有红色的、且重量为 0.2kg 的商品  
SELECT \* FROM products  
WHERE attributes @\> '{"color": "red", "weight\_kg": 0.2}';

#### **6.2.2. 按键是否存在查询**

使用 ? (exists) 操作符。

\-- 查询所有定义了 "storage\_gb" 属性的商品  
SELECT \* FROM products  
WHERE attributes ? 'storage\_gb';

#### **6.2.3. 按数组元素查询**

依然使用 @\> 操作符。

\-- 查询标签包含 "sale" 的商品  
SELECT \* FROM products  
WHERE attributes @\> '{"tags": \["sale"\]}';

#### **6.2.4. 按数值范围查询**

需要将 JSONB 值转换为数值类型进行比较。

\-- 查询重量大于 0.18kg 的商品  
SELECT \* FROM products  
WHERE (attributes-\>\>'weight\_kg')::numeric \> 0.18;

*(性能提示：如 4.2 所述，若此查询极频繁，请建立 B-Tree 表达式索引)*

### **6.3. U (Update) \- 更新**

#### **6.3.1. 新增或覆盖键值**

使用 || (concatenate) 操作符。

\-- 为 id=1 的商品更新颜色（覆盖），并添加新属性 "storage\_gb"  
UPDATE products  
SET attributes \= attributes || '{"color": "black", "storage\_gb": 256}'  
WHERE id \= 1;

#### **6.3.2. 删除键**

使用 \- (delete key) 操作符。

\-- 删除 id=1 的 "tags" 属性  
UPDATE products  
SET attributes \= attributes \- 'tags'  
WHERE id \= 1;

#### **6.3.3. 更新嵌套 JSON (高级)**

使用 jsonb\_set 函数。

\-- 假设 attributes 为: '{"specs": {"cpu": "i5", "ram": 8}}'  
\-- 只更新 specs.ram 到 16  
UPDATE products  
SET attributes \= jsonb\_set(attributes, '{specs, ram}', '16'::jsonb)  
WHERE id \= 1;

### **6.4. D (Delete) \- 删除**

标准 DELETE 语句即可，attributes 列会随行数据一同被删除。

DELETE FROM products WHERE id \= 1;  
