# **PostgreSQL 系统标签能力开发规范v1.3** 

## **1\. 概述**

本文档旨在为基于 PostgreSQL 的业务系统定义一套高性能、可扩展、易维护的标签系统实现规范。

### **核心目标：**

* **高性能查询：** 实现标签的快速筛选（过滤）和匹配度计分（排序）。  
* **数据一致性：** 保证标签数据的规范化，杜绝冗余和不一致，使“重命名”等操作变得简单。  
* **高可扩展性：** 支持亿级别主表数据和千万级别标签库的性能要求。

### **选型结论：**

本规范强制采用\*\*“规范化 int\[\] \+ intarray 扩展”\*\*方案。

* **反规范化方案 (如 JSONB 或 varchar\[\])**  
  * **规范：** 禁止使用。  
* **bigint\[\] 方案**  
  * **规范：** 严厉禁止。

## **2\. 核心架构设计**

本规范的核心是使用两个表（或多对一的属性表）和一个扩展来实现功能。

### **2.1 数据库扩展 (必须)**

* **规范：** 必须在数据库中启用 intarray 扩展。  
  \-- 在数据库中执行一次  
  CREATE EXTENSION IF NOT EXISTS intarray;

### **2.2 表结构设计**

#### **2.2.1 标签主表 (Tag Master) / 字典表**

用于存储和映射所有唯一的“多对多 (M-to-N)” 标签。

\-- cms\_article\_tag\_dict (标签字典表, 示例)  
CREATE TABLE cms\_article\_tag\_dict (  
    \-- 规范: 必须是 SERIAL (int4)，因为 intarray 扩展仅支持 int4。  
    id SERIAL PRIMARY KEY,  
      
    \-- 规范: 使用 VARCHAR(n) 替代 TEXT  
    category VARCHAR(10) NOT NULL DEFAULT '',   
    tag\_value VARCHAR(10) NOT NULL DEFAULT '',

    \-- 规范: 保证数据一致性  
    UNIQUE(category, tag\_value),   
      
    created\_at TIMESTAMPTZ DEFAULT NOW(),  
    description VARCHAR(255)  
);

#### **2.2.2 内容主表 (Main Content Table) / 实体表**

主表现在包含 “属性 (N-to-1)” 和 “标签 (M-to-N)” 两类数据。

\-- cms\_article (内容主表, 示例)  
CREATE TABLE cms\_article (  
    \-- 规范: 必须是 BIGSERIAL  
    id BIGSERIAL PRIMARY KEY,  
      
    title VARCHAR(255),  
    content TEXT,  
      
    \-- 属性 (Attributes) \- N-to-1 (多对一)  
    author\_id BIGINT, \-- (逻辑外键, 指向 cms\_author 表)  
    article\_type\_id INT, \-- (逻辑外键, 指向 cms\_article\_type\_dict 表)

    \-- 核心：标签 (Tags) \- M-to-N  
    tag\_ids INT\[\] NOT NULL DEFAULT '{}',

    \-- 规范: 必备字段  
    created\_at TIMESTAMPTZ DEFAULT NOW(),  
    updated\_at TIMESTAMPTZ DEFAULT NOW(),  
    is\_deleted SMALLINT NOT NULL DEFAULT 0  
);

#### **2.2.3 属性表 (Attribute Tables) / 实体表或字典表**

\-- (另需创建 属性表, 例如:)

\-- cms\_author (实体表)  
CREATE TABLE cms\_author (  
    id BIGSERIAL PRIMARY KEY,  
    name VARCHAR(100) UNIQUE NOT NULL,  
    created\_at TIMESTAMPTZ DEFAULT NOW(),  
    updated\_at TIMESTAMPTZ DEFAULT NOW(),  
    is\_deleted SMALLINT NOT NULL DEFAULT 0  
);

\-- cms\_article\_type\_dict (字典表)  
CREATE TABLE cms\_article\_type\_dict (  
    id SERIAL PRIMARY KEY,  
    code VARCHAR(50) UNIQUE NOT NULL, \-- 业务主键  
    name VARCHAR(100) NOT NULL,  
    created\_at TIMESTAMPTZ DEFAULT NOW()  
);

* **规范 1：** “属性”表（如作者、文章类型）必须与“标签”表（...tag\_dict）严格区分。

### **2.3 索引规范**

* **规范 1：** 标签 (M-to-N) 字段 (tag\_ids) 必须使用 GIN 索引，并指定 gin\_\_int\_ops 操作符类。  
  CREATE INDEX idx\_cms\_article\_tag\_ids\_gin  
  ON cms\_article  
  USING GIN (tag\_ids gin\_\_int\_ops);

* **规范 2：** 属性 (N-to-1) 字段 (如 author\_id) 必须使用标准 B-Tree 索引。  
  CREATE INDEX idx\_cms\_article\_author\_id  
  ON cms\_article (author\_id);

* **规范 3：** 索引命名必须遵循 idx\_... 或 uk\_... 规范。

## **3\. 核心操作规范与用例**

### **3.1 用例一：写入/更新 (混合模型)**

* **规范：** 写入时必须在应用层明确区分 "属性" 和 "标签"，分别获取 ID 后再存入 cms\_article 表。

### **3.2 用例二：写入/更新文章标签 (数据库函数)**

\-- 函数接受 category 和 tag\_value  
CREATE OR REPLACE FUNCTION get\_or\_create\_cms\_article\_tag\_id(  
    p\_category VARCHAR,  
    p\_tag\_value VARCHAR)  
RETURNS INT AS $$  
DECLARE  
    v\_id INT;  
    v\_category VARCHAR(10) := COALESCE(p\_category, '');  
    v\_tag\_value VARCHAR(10) := COALESCE(p\_tag\_value, '');  
BEGIN  
    \-- 1\. 尝试获取 ID  
    \-- 规范: 现已改为 \= 配合 COALESCE  
    SELECT id INTO v\_id FROM cms\_article\_tag\_dict  
    WHERE category \= v\_category  
      AND tag\_value \= v\_tag\_value;  
      
    \-- 2\. 如果找到了，直接返回  
    IF FOUND THEN  
        RETURN v\_id;  
    END IF;

    \-- 3\. 如果没找到，插入新标签  
    BEGIN  
        INSERT INTO cms\_article\_tag\_dict (category, tag\_value)  
        VALUES (v\_category, v\_tag\_value)  
        RETURNING id INTO v\_id;  
        RETURN v\_id;  
    EXCEPTION   
        \-- 4\. 处理并发冲突  
        WHEN unique\_violation THEN  
            SELECT id INTO v\_id FROM cms\_article\_tag\_dict  
            WHERE category \= v\_category  
              AND tag\_value \= v\_tag\_value;  
            RETURN v\_id;  
    END;  
END;  
$$ LANGUAGE plpgsql;

* **规范：** 在函数中进行 NULL 值比较时，必须使用 IS NOT DISTINCT FROM (空值安全等于)。

### **3.3 用例三：核心查询 (混合模型)**

* **规范：**  
  1. **筛选 (WHERE)：** 必须同时使用 B-Tree 索引 (用于属性) 和 GIN 索引 (用于标签)。  
  2. **计分 (SELECT)：** 只对 M-to-N 的标签进行计分。

\-- ... (WITH 子句获取 target\_tags 和 target\_attributes) ...  
SELECT  
    a.id, a.title,  
    \-- 3\. 计分 (Scoring): 只对 M-to-N 标签计分  
    array\_length( a.tag\_ids & (SELECT ids FROM target\_tags), 1 ) AS match\_score  
FROM  
    cms\_article a  
WHERE  
    \-- 2A. B-Tree 索引 (用于属性)  
    a.author\_id \= (SELECT author\_id FROM target\_attributes)  
    AND  
    \-- 2B. GIN 索引 (用于标签)  
    a.tag\_ids && (SELECT ids FROM target\_tags)  
ORDER BY  
    match\_score DESC, a.id DESC;

### **3.4 用例四：获取文章及其标签名 (用于显示)**

* **规范：** 必须使用 LATERAL unnest 展开 tag\_ids 数组，再 LEFT JOIN 字典表。

SELECT  
    a.id, a.title, au.name AS author\_name, aty.name AS article\_type\_name,  
    \-- 2\. 将关联的标签(M-to-N)重新聚合成 JSON  
    COALESCE(  
        json\_agg(  
            json\_build\_object(  
                'id', t.id,  
                'category', t.category,  
                'value', t.tag\_value  
            )  
        ) FILTER (WHERE t.id IS NOT NULL),  
        '\[\]'::json  
    ) AS tags  
FROM  
    cms\_article a  
\-- 3\. JOIN 属性表 (N-to-1)  
LEFT JOIN cms\_author au ON a.author\_id \= au.id  
LEFT JOIN cms\_article\_type\_dict aty ON a.article\_type\_id \= aty.id  
\-- 4\. 展开和 JOIN 标签表 (M-to-N)  
LEFT JOIN LATERAL unnest(a.tag\_ids) AS t\_id ON true  
LEFT JOIN cms\_article\_tag\_dict t ON t.id \= t\_id  
WHERE  
    a.id \= 123  
GROUP BY  
    a.id, a.title, au.name, aty.name;

### **3.5 用例五：标签元数据管理**

#### **3.5.1 重命名标签 (本方案的核心优势)**

* **规范：** 这是一个简单的 UPDATE 操作，严禁扫描 cms\_article 表。

UPDATE cms\_article\_tag\_dict  
SET tag\_value \= 'PGSQL'  
WHERE id \= 101;

#### **3.5.2 删除标签**

* **规范：** 必须在事务中，(1) 从 ...dict 表删除该标签，(2) 使用 array\_remove() 清理所有 cms\_article 表中的引用。

BEGIN;  
\-- 步骤 1: 从 tags 表删除  
DELETE FROM cms\_article\_tag\_dict WHERE id \= 102;  
\-- 步骤 2: 从所有文章中移除该标签 ID  
UPDATE cms\_article  
  SET tag\_ids \= array\_remove(tag\_ids, 102\)  
  WHERE tag\_ids && ARRAY\[102\];  
COMMIT;

### **3.6 用例六：统计热门标签 (标签云)**

* **规范：** 使用 unnest 展开 tag\_ids，然后 GROUP BY 计数。

SELECT  
    t.id, t.category, t.tag\_value,  
    COUNT(\*) AS tag\_count  
FROM  
    cms\_article a,  
    unnest(a.tag\_ids) AS t\_id \-- 展开  
JOIN  
    cms\_article\_tag\_dict t ON t.id \= t\_id  
GROUP BY  
    t.id, t.category, t.tag\_value  
ORDER BY  
    tag\_count DESC  
LIMIT 20;  
