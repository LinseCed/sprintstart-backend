-- Compaction moves off the buddy's answering path.
--
-- A record of what changed and why, not an instruction: this fork builds its schema from the
-- entities (`ddl-auto: update`) and Flyway is not on the classpath, so nothing here has ever run.
-- See backend#174.
--
-- Folding older turns into the mentor's durable memory note used to ride the agent turn, and the
-- AI service performed it *before* composing a reply. Because the cursor advanced by exactly what
-- it folded, the active window sat at WINDOW = 20 forever once it first filled -- so past roughly
-- ten exchanges in a sitting, every turn paid an extra serialized model call, ahead of the answer
-- the hire was waiting for, to compress a single exchange. BuddyCompactionService now does it
-- after a turn finishes.
--
-- Two columns follow from that.

-- 1. The swap needs a lock, not a re-check.
--
-- The fold reads the session, calls the model outside any transaction, then writes the note back;
-- a turn may have moved the cursor in between. The service re-reads and compares, but a re-check
-- alone is not enough -- backend#170 is the local precedent, where read-then-insert with no unique
-- index started two assessment sessions at once and narrowing the window did not close it.
alter table buddy_sessions
    add column version bigint not null default 0;

-- 2. A visit needs a boundary of its own.
--
-- "Does this visit already have a greeting?" and "what does the hire's transcript show?" were both
-- answered from buddy_sessions.summarized_count, which read as "this visit" only because opening a
-- visit advanced that cursor to the end of the transcript. A cursor moved by a background pass
-- marks nothing a person would recognise, and borrowing it would have regenerated a greeting on
-- every refresh -- the bug #176 fixed.
--
-- Read as *the last message is an opening*, never *an opening exists*: a visit ends when the hire
-- speaks, and speaking is exactly what puts a message after the greeting.
alter table buddy_messages
    add column opening boolean not null default false;
