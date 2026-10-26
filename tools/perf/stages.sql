-- Custom Android atrace sections, including their actual execution thread.
SELECT s.name, CASE WHEN t.tid=p.pid THEN 'main' ELSE 'worker' END AS execution,
       COUNT(*) AS calls, ROUND(AVG(s.dur)/1e6,3) AS mean_ms,
       ROUND(MAX(s.dur)/1e6,3) AS max_ms, ROUND(SUM(s.dur)/1e6,1) AS total_ms
FROM slice s
JOIN thread_track tt ON tt.id=s.track_id
JOIN thread t ON t.utid=tt.utid
JOIN process p ON p.upid=t.upid
WHERE s.name GLOB 'BMenetrend.*' AND s.dur>=0
GROUP BY s.name,execution ORDER BY s.name,execution;
