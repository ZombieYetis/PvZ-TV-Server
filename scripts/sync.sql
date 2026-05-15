ATTACH DATABASE '__SLAVE_DB__' AS slave;

BEGIN TRANSACTION;

INSERT INTO main.match_results (
  settle_id, room_id, winner, duration_text, bg, battle_type, game_mode,
  extra_packet, extended_seeds, ban_mode, balance_patch,
  mower_loss, target_loss, sunflower_loss, grave_loss, plant_name, zombie_name, finished_at
)
SELECT
  s.settle_id, s.room_id, s.winner, s.duration_text, s.bg, s.battle_type, s.game_mode,
  s.extra_packet, s.extended_seeds, s.ban_mode, s.balance_patch,
  s.mower_loss, s.target_loss, s.sunflower_loss, s.grave_loss, COALESCE(s.plant_name,''), COALESCE(s.zombie_name,''), s.finished_at
FROM slave.match_results s
LEFT JOIN main.match_results m ON m.settle_id = s.settle_id
WHERE m.settle_id IS NULL;

INSERT INTO main.match_card_events (match_id, seq, side, event_type, seed_type, seed_name, created_at)
SELECT
  m.id, e.seq, e.side, e.event_type, e.seed_type, e.seed_name, e.created_at
FROM slave.match_card_events e
JOIN slave.match_results sr ON sr.id = e.match_id
JOIN main.match_results m ON m.settle_id = sr.settle_id
LEFT JOIN main.match_card_events me
  ON me.match_id = m.id AND me.seq = e.seq AND me.side = e.side AND me.event_type = e.event_type AND me.seed_type = e.seed_type
WHERE me.id IS NULL;

INSERT INTO main.match_card_usage (match_id, side, seed_type, seed_name, use_count, created_at)
SELECT
  m.id, u.side, u.seed_type, u.seed_name, u.use_count, u.created_at
FROM slave.match_card_usage u
JOIN slave.match_results sr ON sr.id = u.match_id
JOIN main.match_results m ON m.settle_id = sr.settle_id
LEFT JOIN main.match_card_usage mu
  ON mu.match_id = m.id AND mu.side = u.side AND mu.seed_type = u.seed_type
WHERE mu.id IS NULL;

DELETE FROM main.card_stats;

INSERT INTO main.card_stats(seed_type, picked, banned, won, updated_at)
SELECT
  seed_type,
  SUM(CASE WHEN event_type='PICK' THEN 1 ELSE 0 END) AS picked,
  SUM(CASE WHEN event_type='BAN' THEN 1 ELSE 0 END) AS banned,
  SUM(CASE WHEN event_type='PICK' AND (
      (side='PLANT' AND mr.winner='PLANT') OR
      (side='ZOMBIE' AND mr.winner='ZOMBIE')
  ) THEN 1 ELSE 0 END) AS won,
  datetime('now')
FROM main.match_card_events e
JOIN main.match_results mr ON mr.id = e.match_id
GROUP BY seed_type;

COMMIT;

DETACH DATABASE slave;
