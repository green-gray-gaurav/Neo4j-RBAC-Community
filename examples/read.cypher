MATCH (c:Customer)
WHERE c.active = true
WITH c.city AS city, count(*) AS customers
RETURN city, customers
ORDER BY customers DESC
LIMIT 20;
