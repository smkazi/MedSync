-- The audit report's filters, given an index each.
--
-- audit_log already has idx_audit_occurred (occurred_at DESC), idx_audit_entity (entity, entity_id)
-- and idx_audit_actor (actor_id). Username had none, because until now nothing filtered on it: the
-- report offered entity, action and actor only. It is about to become a routine filter -- it is the
-- one a person can type from memory, since nobody knows a colleague's UUID -- and the query
-- lower-cases it, so a plain index on the column would not be used at all.
CREATE INDEX idx_audit_username_lower ON audit_log (lower(username));

-- Action was filterable and unindexed too. Every report a person actually runs is "what happened,
-- of this kind, in this period", so the two columns are indexed together in that order rather than
-- separately: the planner can then walk one index for the whole predicate.
CREATE INDEX idx_audit_action_occurred ON audit_log (action, occurred_at DESC);
