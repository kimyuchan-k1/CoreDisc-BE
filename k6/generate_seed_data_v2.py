#!/usr/bin/env python3
"""
CoreDisc Load Test Data Generator v2
Power-law distribution with tier-based user archetypes.

Generates realistic test data where a small number of "influencer" users
have disproportionately more followers, posts, and engagement.

Usage:
  python3 k6/generate_seed_data_v2.py
"""

import os
import sys
import json
import random
import string
import math
from datetime import datetime, timedelta, date
from pathlib import Path
from collections import defaultdict

# ─── Configuration ─────────────────────────────────────────
OUTPUT_DIR = Path("/tmp/coredisc-seed-v2")
MANIFEST_PATH = OUTPUT_DIR / "manifest.json"
BATCH_SIZE = 5000

NUM_MEMBERS = 10_000
NUM_CATEGORIES = 20
NUM_OFFICIAL_QUESTIONS = 50_000
NUM_PERSONAL_QUESTIONS = 30_000

# BCrypt hash for "testpass123a"
PASSWORD_HASH = "$2a$10$ddy4uPlkGmk/niQqdMwj..dmsvMtBjGPV/cbt1HQH6QlAz0XufpKa"

# ─── Tier Definitions ─────────────────────────────────────
# fmt: off
TIERS = {
    "T1": {"name": "Influencer",   "range": (1, 10),       "followers": (5000, 8000), "posts": (500, 800), "publicity": (80, 15, 5),  "circle_pct": 0.60},
    "T2": {"name": "Active",       "range": (11, 510),     "followers": (200, 500),   "posts": (100, 200), "publicity": (60, 30, 10), "circle_pct": 0.40},
    "T3": {"name": "Normal",       "range": (511, 5010),   "followers": (20, 50),     "posts": (10, 30),   "publicity": (55, 25, 20), "circle_pct": 0.25},
    "T4": {"name": "Low-activity", "range": (5011, 9010),  "followers": (0, 5),       "posts": (1, 5),     "publicity": (50, 20, 30), "circle_pct": 0.15},
    "T5": {"name": "Cold-start",   "range": (9011, 10000), "followers": (0, 0),       "posts": (0, 0),     "publicity": (0, 0, 0),    "circle_pct": 0.0},
}
# fmt: on

# ─── Korean text pools ─────────────────────────────────────
KOREAN_SENTENCES = [
    "오늘은 정말 좋은 하루였다.",
    "친구들과 맛있는 점심을 먹었다.",
    "운동을 열심히 했더니 기분이 좋다.",
    "새로운 카페를 발견했는데 분위기가 너무 좋았다.",
    "오랜만에 가족들과 저녁을 함께 했다.",
    "회사에서 프로젝트가 잘 마무리되었다.",
    "산책하면서 봄꽃이 피는 걸 봤다.",
    "좋아하는 음악을 들으며 하루를 마무리했다.",
    "일찍 일어나서 아침 운동을 했다.",
    "새로 나온 책을 읽기 시작했다.",
    "요리를 해서 맛있게 먹었다.",
    "영화를 보면서 감동받았다.",
    "비 오는 날 따뜻한 차 한잔의 여유.",
    "오늘 배운 것들을 정리해봤다.",
    "반려동물과 함께 즐거운 시간을 보냈다.",
    "계획했던 일들을 모두 완료했다.",
    "맑은 하늘을 보니 기분이 상쾌하다.",
    "주말에 여행을 다녀왔는데 너무 좋았다.",
    "오래된 친구를 만나서 수다를 떨었다.",
    "오늘은 일찍 잠들어야겠다.",
    "새로운 취미를 시작했다. 너무 재밌다.",
    "하루 종일 집에서 쉬면서 에너지를 충전했다.",
    "맛집을 찾아 먹방 투어를 했다.",
    "오늘의 감사한 일 세가지를 적어본다.",
    "목표를 향해 한 걸음 더 나아갔다.",
]

KOREAN_QUESTIONS = [
    "오늘 가장 기억에 남는 순간은?",
    "오늘 감사한 일 세 가지는?",
    "지금 가장 하고 싶은 일은?",
    "오늘 새롭게 배운 것은?",
    "지금 기분을 색으로 표현한다면?",
    "오늘 먹은 음식 중 가장 맛있었던 것은?",
    "이번 주 가장 행복했던 순간은?",
    "요즘 가장 관심있는 것은?",
    "지금 듣고 싶은 노래는?",
    "오늘 하루를 한 단어로 표현한다면?",
    "내일의 나에게 하고 싶은 말은?",
    "최근에 읽은 책이나 영화는?",
    "지금 가장 보고 싶은 사람은?",
    "오늘의 에너지 레벨은 몇 점?",
    "이번 달 꼭 이루고 싶은 목표는?",
    "요즘 즐겨 먹는 간식은?",
    "가장 최근에 웃었던 순간은?",
    "오늘 가장 열심히 한 일은?",
    "지금 가장 걱정되는 것은?",
    "스트레스 해소 방법은?",
]

CATEGORY_NAMES = [
    "일상", "감정", "관계", "목표", "감사",
    "성장", "취미", "건강", "음식", "여행",
    "가족", "친구", "직장", "학교", "계절",
    "추억", "미래", "독서", "음악", "운동",
]

NOTIFICATION_TYPES = [
    "DAILY_REMINDER", "DAILY_REMINDER_ANSWER", "UNANSWERED_QUESTION",
    "UNANSWERED_QUESTION_ANSWER", "MONTHLY_REPORT", "FOLLOW",
    "SHARED_SAVED", "COMMENT", "COMMENT_REPLY", "LIKE", "TEMP_POSTS",
]

PUBLICITY_TYPES = ["OFFICIAL", "CIRCLE", "PERSONAL"]
DIARY_WHO = ["ALONE", "FRIEND", "FAMILY", "COLLEAGUE", "LOVER", "PET"]
DIARY_WHERE = ["HOME", "COMPANY", "SCHOOL", "CAFE", "OUTDOOR", "ON_THE_MOVE"]
DIARY_WHAT = ["WORK", "STUDY", "EXERCISE", "REST", "SLEEP", "HOBBY"]
DISC_COLORS = ["WHITE", "GREEN", "YELLOW", "BLUE", "PURPLE", "PINK", "LAVENDER", "MINT", "ORANGE"]

SEARCH_KEYWORDS = [
    "친구", "일상", "운동", "음악", "맛집",
    "여행", "독서", "카페", "산책", "영화",
    "요리", "반려동물", "취미", "힐링", "공부",
]


# ─── Utilities ─────────────────────────────────────────────
def escape_sql(s):
    if s is None:
        return "NULL"
    return "'" + str(s).replace("\\", "\\\\").replace("'", "\\'") + "'"


def sql_ts(dt):
    return escape_sql(dt.strftime("%Y-%m-%d %H:%M:%S"))


def sql_date(d):
    return escape_sql(d.strftime("%Y-%m-%d"))


def random_ts(start_date, end_date):
    delta = end_date - start_date
    random_days = random.randint(0, max(0, delta.days))
    random_seconds = random.randint(0, 86399)
    return start_date + timedelta(days=random_days, seconds=random_seconds)


def random_korean_text(min_len=50, max_len=200):
    text = ""
    while len(text) < min_len:
        text += random.choice(KOREAN_SENTENCES) + " "
    if len(text) > max_len:
        text = text[:max_len]
    return text.strip()


def progress(current, total, label):
    pct = current / total * 100
    if current % max(total // 20, 1) == 0 or current == total:
        print(f"\r  [{pct:5.1f}%] {label}: {current:,}/{total:,}", end="", flush=True, file=sys.stderr)
    if current == total:
        print(file=sys.stderr)


def get_tier(member_id):
    for tier_name, t in TIERS.items():
        lo, hi = t["range"]
        if lo <= member_id <= hi:
            return tier_name
    return "T5"


def pick_publicity(tier_name):
    o, c, p = TIERS[tier_name]["publicity"]
    r = random.randint(1, 100)
    if r <= o:
        return "OFFICIAL"
    elif r <= o + c:
        return "CIRCLE"
    else:
        return "PERSONAL"


# ─── SQL File Writer ───────────────────────────────────────
class SqlWriter:
    def __init__(self, filepath, table_name, columns):
        self.filepath = filepath
        self.table_name = table_name
        self.columns = columns
        self.file = open(filepath, "w", encoding="utf-8")
        self.buffer = []
        self.total_rows = 0
        self.file.write(f"-- {table_name} data (v2 power-law)\n")

    def add_row(self, values):
        self.buffer.append(f"({','.join(str(v) for v in values)})")
        if len(self.buffer) >= BATCH_SIZE:
            self._flush()

    def _flush(self):
        if not self.buffer:
            return
        cols = ",".join(self.columns)
        self.file.write(f"INSERT INTO `{self.table_name}` ({cols}) VALUES\n")
        self.file.write(",\n".join(self.buffer))
        self.file.write(";\n")
        self.total_rows += len(self.buffer)
        self.buffer = []

    def close(self):
        self._flush()
        self.file.close()
        size_mb = os.path.getsize(self.filepath) / (1024 * 1024)
        print(f"  {self.table_name}: {self.total_rows:,} rows, {size_mb:.1f} MB", file=sys.stderr)


# ─── Data Generator ───────────────────────────────────────
class DataGeneratorV2:
    def __init__(self):
        self.output_dir = OUTPUT_DIR
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.start_date = datetime(2024, 1, 1)
        self.end_date = datetime(2026, 2, 19)

        # State tracked across generation phases
        self.member_posts = defaultdict(list)  # member_id -> [post_id]
        self.post_owner = {}                   # post_id -> member_id
        self.post_publicity = {}               # post_id -> publicity type
        self.post_like_counts = defaultdict(int)
        self.post_comment_counts = defaultdict(int)
        self.follow_set = set()                # (follower, following)
        self.mutual_follows = set()            # (a, b) where a < b
        self.circle_pairs = []                 # (a, b) = a marked b as circle
        self.block_pairs = []                  # (blocker, blocked)
        self.hot_post_ids = []
        self.total_posts = 0
        self.total_comments = 0
        self.total_likes = 0
        self.total_follows = 0
        self.total_blocks = 0
        self.total_notifications = 0

    def generate_all(self):
        print("=== CoreDisc Data Generator v2 (Power-law) ===", file=sys.stderr)
        print(f"Output directory: {self.output_dir}", file=sys.stderr)
        print(file=sys.stderr)

        self._write_header()

        # Phase 1: Base data
        self.generate_categories()
        self.generate_members()
        self.generate_profile_imgs()
        self.generate_member_terms()
        self.generate_notification_reminder_settings()
        self.generate_official_questions()
        self.generate_personal_questions()
        self.generate_question_categories()

        # Phase 2: Social graph (must be before posts for circle visibility)
        self.generate_follows()
        self.generate_blocks()

        # Phase 3: Content (power-law distributed)
        self.generate_posts()
        self.generate_post_answers()
        self.generate_comments()
        self.generate_post_likes()

        # Phase 4: Update denormalized counts
        self.update_post_counts()

        # Phase 5: Auxiliary data
        self.generate_notifications()
        self.generate_notification_reads()
        self.generate_discs()
        self.generate_search_history()
        self.generate_devices()
        self.generate_today_questions()

        # Phase 6: Output
        self._write_loader()
        self._write_manifest()

        total_size = sum(
            f.stat().st_size for f in self.output_dir.glob("*.sql")
        ) / (1024 * 1024)
        print(f"\nTotal SQL size: {total_size:.0f} MB", file=sys.stderr)
        print(f"Manifest: {MANIFEST_PATH}", file=sys.stderr)
        print(f"\nRun: bash {self.output_dir}/load.sh", file=sys.stderr)

    # ─── Header & Loader ──────────────────────────────────

    def _write_header(self):
        header_path = self.output_dir / "00_header.sql"
        with open(header_path, "w") as f:
            f.write("SET FOREIGN_KEY_CHECKS = 0;\n")
            f.write("SET UNIQUE_CHECKS = 0;\n")
            f.write("SET AUTOCOMMIT = 0;\n")
            f.write("SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO';\n\n")
            tables = [
                "notification_read", "notification", "post_like", "comment",
                "post_answer_image", "post_answer", "post",
                "today_question", "member_official_question",
                "question_category", "personal_question", "official_question",
                "search_history", "device", "disc", "follow", "block",
                "notification_reminder_setting", "member_terms",
                "profile_img", "member", "category",
                "monthly_fixed_question_stat", "daily_answer_hour_stat",
                "daily_random_question_stat", "monthly_selection_diary_stat",
            ]
            for t in tables:
                f.write(f"TRUNCATE TABLE `{t}`;\n")
            f.write("\n")

    def _write_loader(self):
        loader_path = self.output_dir / "load.sh"
        sql_files = sorted(self.output_dir.glob("*.sql"))
        with open(loader_path, "w") as f:
            f.write("#!/bin/bash\n")
            f.write("# Load v2 seed data into MySQL\n")
            f.write("set -e\n\n")
            f.write('MYSQL="/usr/local/mysql/bin/mysql"\n')
            f.write('DB_USER="root"\n')
            f.write('DB_PASS="kim980618"\n')
            f.write('DB_NAME="coredisc"\n')
            f.write(f'SQL_DIR="{self.output_dir}"\n\n')
            f.write('echo "Loading v2 seed data into $DB_NAME..."\n')
            f.write('START=$(date +%s)\n\n')
            for sql_file in sql_files:
                if sql_file.name == "load.sh":
                    continue
                f.write(f'echo "  Loading {sql_file.name}..."\n')
                f.write(f'$MYSQL -u $DB_USER -p$DB_PASS $DB_NAME < "$SQL_DIR/{sql_file.name}"\n\n')
            f.write('# Re-enable checks\n')
            f.write('$MYSQL -u $DB_USER -p$DB_PASS $DB_NAME -e "\n')
            f.write('SET FOREIGN_KEY_CHECKS = 1;\n')
            f.write('SET UNIQUE_CHECKS = 1;\n')
            f.write('SET AUTOCOMMIT = 1;\n')
            f.write('COMMIT;\n')
            f.write('"\n\n')
            f.write('END=$(date +%s)\n')
            f.write('ELAPSED=$((END - START))\n')
            f.write('echo ""\n')
            f.write('echo "Done in ${ELAPSED}s"\n')
            f.write('echo "Database size:"\n')
            f.write('$MYSQL -u $DB_USER -p$DB_PASS -e "\n')
            f.write("SELECT table_schema AS db, \n")
            f.write("  ROUND(SUM(data_length + index_length) / 1024 / 1024, 1) AS size_mb\n")
            f.write("FROM information_schema.tables\n")
            f.write(f"WHERE table_schema = '$DB_NAME'\n")
            f.write('GROUP BY table_schema;"\n')
        os.chmod(loader_path, 0o755)

    def _write_manifest(self):
        manifest = {
            "generated_at": datetime.now().isoformat(),
            "version": "v2",
            "tiers": {},
            "hot_post_ids": self.hot_post_ids[:10],
            "circle_pairs": self.circle_pairs[:200],
            "block_pairs": self.block_pairs[:100],
            "stats": {
                "total_members": NUM_MEMBERS,
                "total_posts": self.total_posts,
                "total_comments": self.total_comments,
                "total_likes": self.total_likes,
                "total_follows": self.total_follows,
                "total_blocks": self.total_blocks,
                "total_circle_pairs": len(self.circle_pairs),
                "total_notifications": self.total_notifications,
            },
        }
        for tier_name, t in TIERS.items():
            lo, hi = t["range"]
            manifest["tiers"][tier_name] = {
                "name": t["name"],
                "member_ids": list(range(lo, hi + 1)),
                "count": hi - lo + 1,
            }

        with open(MANIFEST_PATH, "w") as f:
            json.dump(manifest, f, indent=2, ensure_ascii=False)
        print(f"  manifest.json written", file=sys.stderr)

    # ─── Base Data ────────────────────────────────────────

    def generate_categories(self):
        print("[01] Generating categories...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "01_category.sql", "category",
            ["id", "name", "created_at", "updated_at"]
        )
        now = sql_ts(datetime.now())
        for i in range(1, NUM_CATEGORIES + 1):
            w.add_row([i, escape_sql(CATEGORY_NAMES[i - 1]), now, now])
        w.close()

    def generate_members(self):
        print("[02] Generating members...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "02_member.sql", "member",
            ["id", "email", "username", "password", "nickname", "name",
             "status", "is_social_login", "created_at", "updated_at"]
        )
        for i in range(1, NUM_MEMBERS + 1):
            ts = sql_ts(random_ts(self.start_date, self.end_date))
            nickname = f"nick{i:05d}"
            w.add_row([
                i,
                escape_sql(f"user{i}@loadtest.local"),
                escape_sql(f"loaduser_{i}"),
                escape_sql(PASSWORD_HASH),
                escape_sql(nickname),
                escape_sql(f"User{i}"),
                1, 0, ts, ts,
            ])
            progress(i, NUM_MEMBERS, "members")
        w.close()

    def generate_profile_imgs(self):
        print("[03] Generating profile images...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "03_profile_img.sql", "profile_img",
            ["id", "img_url", "member_id", "created_at", "updated_at"]
        )
        now = sql_ts(datetime.now())
        w.add_row([1, escape_sql("https://local-stub/default-profile.png"), "NULL", now, now])
        for i in range(1, NUM_MEMBERS + 1):
            w.add_row([
                i + 1,
                escape_sql(f"https://local-stub/profiles/user{i}.png"),
                i, now, now,
            ])
            progress(i, NUM_MEMBERS, "profile_imgs")
        w.close()

    def generate_member_terms(self):
        print("[04] Generating member terms...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "04_member_terms.sql", "member_terms",
            ["id", "member_id", "terms_id", "created_at", "updated_at"]
        )
        now = sql_ts(datetime.now())
        idx = 1
        for member_id in range(1, NUM_MEMBERS + 1):
            for terms_id in [1, 2, 3]:
                w.add_row([idx, member_id, terms_id, now, now])
                idx += 1
            progress(member_id, NUM_MEMBERS, "member_terms")
        w.close()

    def generate_notification_reminder_settings(self):
        print("[05] Generating notification reminder settings...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "05_notification_reminder_setting.sql",
            "notification_reminder_setting",
            ["id", "member_id", "daily_reminder_enabled", "unanswered_reminder_enabled",
             "daily_reminder_time", "unanswered_reminder_time"]
        )
        for i in range(1, NUM_MEMBERS + 1):
            w.add_row([
                i, i, 1, 1,
                escape_sql("21:00:00"),
                escape_sql("20:00:00"),
            ])
            progress(i, NUM_MEMBERS, "reminder_settings")
        w.close()

    def generate_official_questions(self):
        print("[06] Generating official questions...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "06_official_question.sql", "official_question",
            ["id", "is_shared", "contents", "member_id", "created_at", "updated_at"]
        )
        for i in range(1, NUM_OFFICIAL_QUESTIONS + 1):
            member_id = random.randint(1, NUM_MEMBERS)
            ts = sql_ts(random_ts(self.start_date, self.end_date))
            content = random.choice(KOREAN_QUESTIONS) + f" ({i})"
            w.add_row([i, 1, escape_sql(content), member_id, ts, ts])
            progress(i, NUM_OFFICIAL_QUESTIONS, "official_questions")
        w.close()

    def generate_personal_questions(self):
        print("[07] Generating personal questions...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "07_personal_question.sql", "personal_question",
            ["id", "content", "member_id", "created_at", "updated_at"]
        )
        for i in range(1, NUM_PERSONAL_QUESTIONS + 1):
            member_id = random.randint(1, NUM_MEMBERS)
            ts = sql_ts(random_ts(self.start_date, self.end_date))
            content = random.choice(KOREAN_QUESTIONS) + f" (개인 {i})"
            w.add_row([i, escape_sql(content), member_id, ts, ts])
            progress(i, NUM_PERSONAL_QUESTIONS, "personal_questions")
        w.close()

    def generate_question_categories(self):
        print("[08] Generating question categories...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "08_question_category.sql", "question_category",
            ["id", "category_id", "official_question_id", "personal_question_id",
             "created_at", "updated_at"]
        )
        now = sql_ts(datetime.now())
        idx = 1
        total = NUM_OFFICIAL_QUESTIONS + NUM_PERSONAL_QUESTIONS
        for q_id in range(1, NUM_OFFICIAL_QUESTIONS + 1):
            cat_id = (q_id % NUM_CATEGORIES) + 1
            w.add_row([idx, cat_id, q_id, "NULL", now, now])
            idx += 1
            if q_id % 10000 == 0:
                progress(q_id, total, "question_categories")
        for q_id in range(1, NUM_PERSONAL_QUESTIONS + 1):
            cat_id = (q_id % NUM_CATEGORIES) + 1
            w.add_row([idx, cat_id, "NULL", q_id, now, now])
            idx += 1
        progress(total, total, "question_categories")
        w.close()

    # ─── Social Graph (Power-law) ─────────────────────────

    def generate_follows(self):
        """
        Generate follow graph with power-law distribution.
        T1 users attract 50x weight, T2 5x, T3 1x for being followed.

        Circle logic (from FollowCommandServiceImpl):
        When A marks B as circle, the follow row with follower_id=B, following_id=A
        gets is_circle=true. So in the follow table:
        - follower_id=B, following_id=A, is_circle=1
        means "A considers B a close friend".
        """
        print("[09] Generating follows (power-law)...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "09_follow.sql", "follow",
            ["id", "follower_id", "following_id", "is_circle", "created_at", "updated_at"]
        )

        # Build weighted pool for "who to follow"
        # Higher tier users are more likely to be followed
        weighted_pool = []
        weights = []
        for member_id in range(1, NUM_MEMBERS + 1):
            tier = get_tier(member_id)
            if tier == "T1":
                w_val = 50
            elif tier == "T2":
                w_val = 5
            elif tier == "T3":
                w_val = 1
            else:
                w_val = 0.1
            weighted_pool.append(member_id)
            weights.append(w_val)

        # Normalize weights for random.choices
        total_weight = sum(weights)
        norm_weights = [w_val / total_weight for w_val in weights]

        # Generate follower counts per member based on tier
        target_followers = {}
        for member_id in range(1, NUM_MEMBERS + 1):
            tier = get_tier(member_id)
            lo, hi = TIERS[tier]["followers"]
            target_followers[member_id] = random.randint(lo, hi)

        # Assign followers
        idx = 1
        follower_counts = defaultdict(int)

        # For each member that should be followed, pick random followers
        for following_id in range(1, NUM_MEMBERS + 1):
            target = target_followers[following_id]
            if target == 0:
                continue

            following_tier = get_tier(following_id)
            attempts = 0
            assigned = 0

            while assigned < target and attempts < target * 5:
                attempts += 1
                # Followers come from all tiers
                follower_id = random.randint(1, NUM_MEMBERS)
                if follower_id == following_id:
                    continue
                if (follower_id, following_id) in self.follow_set:
                    continue

                self.follow_set.add((follower_id, following_id))
                follower_counts[following_id] += 1

                ts = sql_ts(random_ts(self.start_date, self.end_date))
                w.add_row([idx, follower_id, following_id, 0, ts, ts])
                idx += 1
                assigned += 1

            if following_id % 1000 == 0:
                progress(following_id, NUM_MEMBERS, "follows")

        self.total_follows = idx - 1
        progress(NUM_MEMBERS, NUM_MEMBERS, "follows")
        w.close()

        # Identify mutual follows
        print("  Identifying mutual follows...", file=sys.stderr)
        for (a, b) in self.follow_set:
            if (b, a) in self.follow_set:
                key = (min(a, b), max(a, b))
                self.mutual_follows.add(key)
        print(f"  Found {len(self.mutual_follows):,} mutual follow pairs", file=sys.stderr)

        # Generate circle relationships from mutual follows
        self._generate_circles()

    def _generate_circles(self):
        """
        From mutual follows, tier-based probability of marking as circle.
        Circle logic: When user A marks user B as circle,
        the row follower_id=B, following_id=A gets is_circle=true.
        We write UPDATE statements to set is_circle=1 for chosen pairs.
        """
        print("  Generating circle relationships...", file=sys.stderr)
        update_path = self.output_dir / "09b_circle_updates.sql"
        with open(update_path, "w") as f:
            f.write("-- Circle updates\n")
            batch = []
            for (a, b) in self.mutual_follows:
                tier_a = get_tier(a)
                tier_b = get_tier(b)
                circle_pct_a = TIERS[tier_a]["circle_pct"]
                circle_pct_b = TIERS[tier_b]["circle_pct"]

                # A marks B as circle (updates row: follower_id=B, following_id=A)
                if random.random() < circle_pct_a:
                    batch.append(f"UPDATE `follow` SET is_circle=1 WHERE follower_id={b} AND following_id={a};")
                    self.circle_pairs.append([a, b])

                # B marks A as circle (updates row: follower_id=A, following_id=B)
                if random.random() < circle_pct_b:
                    batch.append(f"UPDATE `follow` SET is_circle=1 WHERE follower_id={a} AND following_id={b};")
                    self.circle_pairs.append([b, a])

                if len(batch) >= 1000:
                    f.write("\n".join(batch) + "\n")
                    batch = []

            if batch:
                f.write("\n".join(batch) + "\n")

        size_mb = os.path.getsize(update_path) / (1024 * 1024)
        print(f"  circle_updates: {len(self.circle_pairs):,} pairs, {size_mb:.1f} MB", file=sys.stderr)

    def generate_blocks(self):
        """
        Generate ~500 block relationships, primarily between T2-T3 users.
        Block creation deletes all follow relationships between the pair.
        """
        print("[10] Generating blocks...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "10_block.sql", "block",
            ["id", "blocker_id", "blocked_id", "created_at", "updated_at"]
        )

        block_set = set()
        idx = 1
        target_blocks = 500
        attempts = 0

        while idx <= target_blocks and attempts < target_blocks * 10:
            attempts += 1
            # Blocks primarily between T2-T3
            blocker = random.randint(11, 5010)
            blocked = random.randint(11, 5010)

            if blocker == blocked:
                continue
            if (blocker, blocked) in block_set:
                continue

            block_set.add((blocker, blocked))
            self.block_pairs.append([blocker, blocked])

            # Remove follow relationships (both directions)
            self.follow_set.discard((blocker, blocked))
            self.follow_set.discard((blocked, blocker))

            ts = sql_ts(random_ts(self.start_date, self.end_date))
            w.add_row([idx, blocker, blocked, ts, ts])
            idx += 1

        self.total_blocks = idx - 1
        w.close()

        # Write follow delete statements for blocked pairs
        del_path = self.output_dir / "10b_block_follow_deletes.sql"
        with open(del_path, "w") as f:
            f.write("-- Delete follows for blocked pairs\n")
            for [blocker, blocked] in self.block_pairs:
                f.write(f"DELETE FROM `follow` WHERE (follower_id={blocker} AND following_id={blocked}) "
                        f"OR (follower_id={blocked} AND following_id={blocker});\n")

        size_mb = os.path.getsize(del_path) / (1024 * 1024)
        print(f"  block_follow_deletes: {size_mb:.1f} MB", file=sys.stderr)

    # ─── Content (Power-law) ──────────────────────────────

    def generate_posts(self):
        """Generate posts with tier-based distribution."""
        print("[11] Generating posts (power-law)...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "11_post.sql", "post",
            ["id", "publicity", "status", "daily_who", "daily_where", "daily_what",
             "daily_detail", "like_count", "comment_count", "view_count",
             "member_id", "created_at", "updated_at"]
        )

        post_id = 1
        for member_id in range(1, NUM_MEMBERS + 1):
            tier = get_tier(member_id)
            lo, hi = TIERS[tier]["posts"]
            num_posts = random.randint(lo, hi)

            for _ in range(num_posts):
                ts = sql_ts(random_ts(self.start_date, self.end_date))
                publicity = pick_publicity(tier)
                who = random.choice(DIARY_WHO)
                where = random.choice(DIARY_WHERE)
                what = random.choice(DIARY_WHAT)
                detail = random.choice(KOREAN_SENTENCES)[:200]

                # Placeholder counts (will be updated later)
                w.add_row([
                    post_id, escape_sql(publicity), escape_sql("PUBLISHED"),
                    escape_sql(who), escape_sql(where), escape_sql(what),
                    escape_sql(detail), 0, 0, random.randint(0, 500),
                    member_id, ts, ts,
                ])

                self.member_posts[member_id].append(post_id)
                self.post_owner[post_id] = member_id
                self.post_publicity[post_id] = publicity
                post_id += 1

            if member_id % 1000 == 0:
                progress(member_id, NUM_MEMBERS, "posts")

        self.total_posts = post_id - 1
        progress(NUM_MEMBERS, NUM_MEMBERS, "posts")
        print(f"  Total posts: {self.total_posts:,}", file=sys.stderr)
        w.close()

    def generate_post_answers(self):
        print("[12] Generating post answers + images...", file=sys.stderr)
        w_answer = SqlWriter(
            self.output_dir / "12_post_answer.sql", "post_answer",
            ["id", "answer_order", "type", "text_content", "post_id",
             "created_at", "updated_at"]
        )
        w_image = SqlWriter(
            self.output_dir / "13_post_answer_image.sql", "post_answer_image",
            ["id", "post_answer_id", "img_url", "thumbnail_url", "s3_key",
             "original_file_name", "created_at", "updated_at"]
        )
        answer_id = 1
        image_id = 1
        for post_id in range(1, self.total_posts + 1):
            ts = sql_ts(random_ts(self.start_date, self.end_date))
            num_answers = random.choice([1, 2, 3])
            for order in range(1, num_answers + 1):
                is_text = random.random() < 0.6
                if is_text:
                    content = random.choice(KOREAN_SENTENCES)[:50]
                    w_answer.add_row([
                        answer_id, order, escape_sql("TEXT"),
                        escape_sql(content), post_id, ts, ts,
                    ])
                else:
                    w_answer.add_row([
                        answer_id, order, escape_sql("IMAGE"),
                        "NULL", post_id, ts, ts,
                    ])
                    img_url = f"https://local-stub/images/post{post_id}_a{order}.jpg"
                    thumb_url = f"https://local-stub/thumbnails/post{post_id}_a{order}_thumb.jpg"
                    s3_key = f"images/{post_id}/{order}.jpg"
                    w_image.add_row([
                        image_id, answer_id,
                        escape_sql(img_url), escape_sql(thumb_url),
                        escape_sql(s3_key), escape_sql(f"photo_{post_id}_{order}.jpg"),
                        ts, ts,
                    ])
                    image_id += 1
                answer_id += 1
            if post_id % 20000 == 0:
                progress(post_id, self.total_posts, "post_answers")
        progress(self.total_posts, self.total_posts, "post_answers")
        w_answer.close()
        w_image.close()

    def generate_comments(self):
        """
        Generate comments with power-law: T1 posts attract far more comments.
        """
        print("[13] Generating comments (power-law)...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "14_comment.sql", "comment",
            ["id", "content", "is_deleted", "depth", "post_id", "member_id",
             "parent_id", "created_at", "updated_at"]
        )

        # Build weighted post pool: T1 posts get 50x weight
        post_ids_list = []
        post_weights = []
        for pid in range(1, self.total_posts + 1):
            owner = self.post_owner[pid]
            tier = get_tier(owner)
            if tier == "T1":
                w_val = 50
            elif tier == "T2":
                w_val = 5
            elif tier == "T3":
                w_val = 1
            else:
                w_val = 0.2
            post_ids_list.append(pid)
            post_weights.append(w_val)

        target_comments = 350_000
        top_level_ratio = 0.7
        num_top = int(target_comments * top_level_ratio)

        comment_id = 1
        for i in range(1, target_comments + 1):
            # Pick post with power-law weighting
            post_id = random.choices(post_ids_list, weights=post_weights, k=1)[0]
            member_id = random.randint(1, 5010)  # Only active users comment
            ts = sql_ts(random_ts(self.start_date, self.end_date))
            content = random_korean_text(100, 400)

            if i <= num_top:
                w.add_row([
                    comment_id, escape_sql(content), 0, 0,
                    post_id, member_id, "NULL", ts, ts,
                ])
            else:
                parent_id = random.randint(1, num_top)
                w.add_row([
                    comment_id, escape_sql(content), 0, 1,
                    post_id, member_id, parent_id, ts, ts,
                ])

            self.post_comment_counts[post_id] += 1
            comment_id += 1

            if i % 50000 == 0:
                progress(i, target_comments, "comments")

        self.total_comments = comment_id - 1
        progress(target_comments, target_comments, "comments")
        w.close()

    def generate_post_likes(self):
        """
        Generate likes with power-law: T1 posts get 50-1000 likes.
        """
        print("[14] Generating post likes (power-law)...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "15_post_like.sql", "post_like",
            ["id", "member_id", "post_id", "created_at", "updated_at"]
        )

        seen = set()
        idx = 1

        # T1 posts: heavy likes (50-1000 per post)
        t1_posts = []
        for member_id in range(1, 11):
            t1_posts.extend(self.member_posts[member_id])

        for post_id in t1_posts:
            num_likes = random.randint(50, 1000)
            assigned = 0
            attempts = 0
            while assigned < num_likes and attempts < num_likes * 3:
                attempts += 1
                member_id = random.randint(1, 5010)
                if member_id == self.post_owner[post_id]:
                    continue
                if (post_id, member_id) in seen:
                    continue
                seen.add((post_id, member_id))
                ts = sql_ts(random_ts(self.start_date, self.end_date))
                w.add_row([idx, member_id, post_id, ts, ts])
                self.post_like_counts[post_id] += 1
                idx += 1
                assigned += 1

        # T2 posts: moderate likes (5-50)
        for member_id in range(11, 511):
            for post_id in self.member_posts[member_id]:
                num_likes = random.randint(5, 50)
                assigned = 0
                attempts = 0
                while assigned < num_likes and attempts < num_likes * 3:
                    attempts += 1
                    liker = random.randint(1, 5010)
                    if liker == member_id:
                        continue
                    if (post_id, liker) in seen:
                        continue
                    seen.add((post_id, liker))
                    ts = sql_ts(random_ts(self.start_date, self.end_date))
                    w.add_row([idx, liker, post_id, ts, ts])
                    self.post_like_counts[post_id] += 1
                    idx += 1
                    assigned += 1

            if member_id % 100 == 0:
                progress(member_id - 10, 500, "likes (T2)")

        # T3 posts: few likes (1-10)
        for member_id in range(511, 5011):
            for post_id in self.member_posts[member_id]:
                num_likes = random.randint(1, 10)
                assigned = 0
                attempts = 0
                while assigned < num_likes and attempts < num_likes * 3:
                    attempts += 1
                    liker = random.randint(1, 5010)
                    if liker == member_id:
                        continue
                    if (post_id, liker) in seen:
                        continue
                    seen.add((post_id, liker))
                    ts = sql_ts(random_ts(self.start_date, self.end_date))
                    w.add_row([idx, liker, post_id, ts, ts])
                    self.post_like_counts[post_id] += 1
                    idx += 1
                    assigned += 1

            if member_id % 500 == 0:
                progress(member_id - 510, 4500, "likes (T3)")

        # T4 posts: rare likes (0-2)
        for member_id in range(5011, 9011):
            for post_id in self.member_posts[member_id]:
                num_likes = random.randint(0, 2)
                for _ in range(num_likes):
                    liker = random.randint(1, 5010)
                    if liker == member_id:
                        continue
                    if (post_id, liker) in seen:
                        continue
                    seen.add((post_id, liker))
                    ts = sql_ts(random_ts(self.start_date, self.end_date))
                    w.add_row([idx, liker, post_id, ts, ts])
                    self.post_like_counts[post_id] += 1
                    idx += 1

        self.total_likes = idx - 1
        print(f"  Total likes: {self.total_likes:,}", file=sys.stderr)

        # Identify hot posts (top 10 by likes)
        sorted_posts = sorted(self.post_like_counts.items(), key=lambda x: -x[1])
        self.hot_post_ids = [pid for pid, _ in sorted_posts[:10]]
        print(f"  Hot posts (top 10 by likes): {self.hot_post_ids}", file=sys.stderr)

        w.close()

    def update_post_counts(self):
        """Write UPDATE statements to set accurate like_count and comment_count."""
        print("[15] Updating denormalized post counts...", file=sys.stderr)
        update_path = self.output_dir / "16_post_count_updates.sql"

        all_post_ids = set(self.post_like_counts.keys()) | set(self.post_comment_counts.keys())

        with open(update_path, "w") as f:
            f.write("-- Denormalized count updates\n")
            batch = []
            count = 0
            for post_id in sorted(all_post_ids):
                likes = self.post_like_counts.get(post_id, 0)
                comments = self.post_comment_counts.get(post_id, 0)
                batch.append(
                    f"UPDATE `post` SET like_count={likes}, comment_count={comments} WHERE id={post_id};"
                )
                count += 1
                if len(batch) >= 5000:
                    f.write("\n".join(batch) + "\n")
                    batch = []

            if batch:
                f.write("\n".join(batch) + "\n")

        size_mb = os.path.getsize(update_path) / (1024 * 1024)
        print(f"  post_count_updates: {count:,} rows, {size_mb:.1f} MB", file=sys.stderr)

    # ─── Auxiliary Data ───────────────────────────────────

    def generate_notifications(self):
        """Generate notifications proportional to activity."""
        print("[16] Generating notifications...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "17_notification.sql", "notification",
            ["id", "receiver_id", "sender_id", "type", "content",
             "target_id", "created_at", "updated_at"]
        )

        target = 800_000
        for i in range(1, target + 1):
            # Receivers biased towards active users (T1-T3)
            receiver_id = random.randint(1, 5010)
            sender_id = random.randint(1, 5010)
            noti_type = random.choice(NOTIFICATION_TYPES)
            ts = sql_ts(random_ts(self.start_date, self.end_date))

            if noti_type == "LIKE":
                content = f"nick{sender_id:05d}님이 회원님의 게시물을 좋아합니다."
            elif noti_type == "COMMENT":
                content = f"nick{sender_id:05d}님이 댓글을 남겼습니다: {random.choice(KOREAN_SENTENCES)[:100]}"
            elif noti_type == "COMMENT_REPLY":
                content = f"nick{sender_id:05d}님이 답글을 남겼습니다: {random.choice(KOREAN_SENTENCES)[:100]}"
            elif noti_type == "FOLLOW":
                content = f"nick{sender_id:05d}님이 회원님을 팔로우하기 시작했습니다."
            elif noti_type == "DAILY_REMINDER":
                content = "오늘의 질문에 답변해보세요!"
            elif noti_type == "UNANSWERED_QUESTION":
                content = "아직 답변하지 않은 질문이 있어요. 지금 확인해보세요!"
            elif noti_type == "MONTHLY_REPORT":
                content = "이번 달 리포트가 준비되었습니다. 확인해보세요!"
            else:
                content = f"새로운 알림이 있습니다. {random.choice(KOREAN_SENTENCES)[:80]}"

            target_id = random.randint(1, self.total_posts) if noti_type in ("LIKE", "COMMENT", "COMMENT_REPLY") else "NULL"

            w.add_row([
                i, receiver_id, sender_id, escape_sql(noti_type),
                escape_sql(content), target_id, ts, ts,
            ])
            if i % 100000 == 0:
                progress(i, target, "notifications")

        self.total_notifications = target
        progress(target, target, "notifications")
        w.close()

    def generate_notification_reads(self):
        print("[17] Generating notification reads...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "18_notification_read.sql", "notification_read",
            ["id", "notification_id", "member_id", "is_read"]
        )
        target = self.total_notifications
        for i in range(1, target + 1):
            receiver_id = ((i - 1) % 5010) + 1
            is_read = 1 if random.random() < 0.7 else 0
            w.add_row([i, i, receiver_id, is_read])
            if i % 100000 == 0:
                progress(i, target, "notification_reads")
        progress(target, target, "notification_reads")
        w.close()

    def generate_discs(self):
        print("[18] Generating discs...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "19_disc.sql", "disc",
            ["id", "year", "month", "cover_color", "cover_img_url",
             "member_id", "created_at", "updated_at"]
        )
        now = sql_ts(datetime.now())
        idx = 1
        seen = set()
        target = 100_000
        for member_id in range(1, NUM_MEMBERS + 1):
            num_months = random.randint(1, 24)
            start_month = random.randint(1, 24)
            for m in range(num_months):
                year = 2024 + (start_month + m) // 12
                month = ((start_month + m) % 12) + 1
                key = (year, month, member_id)
                if key in seen:
                    continue
                seen.add(key)
                color = random.choice(DISC_COLORS)
                w.add_row([idx, year, month, escape_sql(color), "NULL", member_id, now, now])
                idx += 1
                if idx > target:
                    break
            if idx > target:
                break
            if member_id % 2000 == 0:
                progress(min(idx, target), target, "discs")
        progress(min(idx - 1, target), target, "discs")
        w.close()

    def generate_search_history(self):
        print("[19] Generating search history...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "20_search_history.sql", "search_history",
            ["id", "keyword", "search_type", "searched_at", "member_id",
             "created_at", "updated_at"]
        )
        target = 100_000
        for i in range(1, target + 1):
            member_id = random.randint(1, 5010)
            keyword = random.choice(SEARCH_KEYWORDS) + str(random.randint(1, 100))
            ts = sql_ts(random_ts(self.start_date, self.end_date))
            w.add_row([i, escape_sql(keyword), escape_sql("MEMBER"), ts, member_id, ts, ts])
            if i % 20000 == 0:
                progress(i, target, "search_history")
        progress(target, target, "search_history")
        w.close()

    def generate_devices(self):
        print("[20] Generating devices...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "21_device.sql", "device",
            ["id", "member_id", "token", "device_type", "is_active",
             "created_at", "updated_at"]
        )
        now = sql_ts(datetime.now())
        for i in range(1, NUM_MEMBERS + 1):
            token = f"fcm_token_{''.join(random.choices(string.ascii_letters + string.digits, k=140))}"
            w.add_row([i, i, escape_sql(token), escape_sql("iOS"), 1, now, now])
            progress(i, NUM_MEMBERS, "devices")
        w.close()

    def generate_today_questions(self):
        """Generate today_questions proportional to activity period per tier."""
        print("[21] Generating today questions...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "22_today_question.sql", "today_question",
            ["id", "selected_date", "question_type", "question_order",
             "member_id", "official_question_id", "personal_question_id",
             "created_at", "updated_at"]
        )
        idx = 1

        for member_id in range(1, NUM_MEMBERS + 1):
            tier = get_tier(member_id)
            # Tier determines activity period
            if tier == "T1":
                num_days = random.randint(300, 600)
            elif tier == "T2":
                num_days = random.randint(150, 400)
            elif tier == "T3":
                num_days = random.randint(50, 200)
            elif tier == "T4":
                num_days = random.randint(5, 30)
            else:
                num_days = 0

            if num_days == 0:
                continue

            base_date = date(2024, 1, 1) + timedelta(days=random.randint(0, 365))
            for d in range(num_days):
                sel_date = base_date + timedelta(days=d)
                if sel_date > date(2026, 2, 19):
                    break
                ts = sql_ts(datetime.combine(sel_date, datetime.min.time()))
                for order in range(1, random.randint(2, 4)):
                    q_type = random.choice(["FIXED", "RANDOM"])
                    if random.random() < 0.7:
                        oq_id = random.randint(1, NUM_OFFICIAL_QUESTIONS)
                        pq_id = "NULL"
                    else:
                        oq_id = "NULL"
                        pq_id = random.randint(1, NUM_PERSONAL_QUESTIONS)
                    w.add_row([
                        idx, sql_date(sel_date), escape_sql(q_type), order,
                        member_id, oq_id, pq_id, ts, ts,
                    ])
                    idx += 1

            if member_id % 1000 == 0:
                progress(member_id, NUM_MEMBERS, "today_questions")

        progress(NUM_MEMBERS, NUM_MEMBERS, "today_questions")
        print(f"  Total today_questions: {idx - 1:,}", file=sys.stderr)
        w.close()


# ─── Main ──────────────────────────────────────────────────
if __name__ == "__main__":
    random.seed(42)
    gen = DataGeneratorV2()
    gen.generate_all()
