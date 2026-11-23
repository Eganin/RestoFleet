-- RestoFleet, этап «практика», ПМ12 (RV-4, реш. 531): из какого токена выпущен refresh при ротации.
-- По нему повтор только что ротированного токена отличается от кражи: преемник ещё не использован — ответ потерян.
-- Внешнего ключа нет: связь нужна только в окне прощения, старые токены могут удаляться независимо.
ALTER TABLE refresh_tokens ADD COLUMN rotated_from uuid;
CREATE INDEX refresh_tokens_rotated_from_idx ON refresh_tokens (rotated_from) WHERE revoked_at IS NULL;
