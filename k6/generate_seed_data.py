#!/usr/bin/env python3
"""
CoreDisc Load Test Data Generator
Generates ~1GB of realistic test data for load testing.

Usage:
  python3 k6/generate_seed_data.py
  ./k6/load_seed.sh
"""

import os
import sys
import random
import string
from datetime import datetime, timedelta, date
from pathlib import Path

# ─── Configuration ─────────────────────────────────────────
OUTPUT_DIR = Path("/tmp/coredisc-seed")
BATCH_SIZE = 5000

# Data volumes (~1GB total)
NUM_MEMBERS = 10_000
NUM_CATEGORIES = 20
NUM_OFFICIAL_QUESTIONS = 50_000
NUM_PERSONAL_QUESTIONS = 30_000
NUM_POSTS = 200_000
NUM_COMMENTS = 500_000
NUM_FOLLOWS = 200_000
NUM_POST_LIKES = 300_000
NUM_NOTIFICATIONS = 1_000_000
NUM_DISCS = 100_000
NUM_SEARCH_HISTORY = 100_000

# BCrypt hash for "testpass123a"
PASSWORD_HASH = "$2a$10$ddy4uPlkGmk/niQqdMwj..dmsvMtBjGPV/cbt1HQH6QlAz0XufpKa"

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

SEARCH_KEYWORDS = [
    "친구", "일상", "운동", "음악", "맛집",
    "여행", "독서", "카페", "산책", "영화",
    "요리", "반려동물", "취미", "힐링", "공부",
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

# ─── Utilities ─────────────────────────────────────────────
def escape_sql(s):
    """Escape single quotes for SQL strings."""
    if s is None:
        return "NULL"
    return "'" + str(s).replace("\\", "\\\\").replace("'", "\\'") + "'"

def sql_ts(dt):
    """Format datetime for SQL."""
    return escape_sql(dt.strftime("%Y-%m-%d %H:%M:%S"))

def sql_date(d):
    """Format date for SQL."""
    return escape_sql(d.strftime("%Y-%m-%d"))

def random_ts(start_date, end_date):
    """Generate random datetime between two dates."""
    delta = end_date - start_date
    random_days = random.randint(0, delta.days)
    random_seconds = random.randint(0, 86399)
    return start_date + timedelta(days=random_days, seconds=random_seconds)

def random_korean_text(min_len=50, max_len=200):
    """Generate random Korean text by combining sentences."""
    text = ""
    while len(text) < min_len:
        text += random.choice(KOREAN_SENTENCES) + " "
    if len(text) > max_len:
        text = text[:max_len]
    return text.strip()

def progress(current, total, label):
    """Print progress bar."""
    pct = current / total * 100
    if current % (total // 20 or 1) == 0 or current == total:
        print(f"\r  [{pct:5.1f}%] {label}: {current:,}/{total:,}", end="", flush=True, file=sys.stderr)
    if current == total:
        print(file=sys.stderr)


# ─── SQL File Writer ───────────────────────────────────────
class SqlWriter:
    def __init__(self, filepath, table_name, columns):
        self.filepath = filepath
        self.table_name = table_name
        self.columns = columns
        self.file = open(filepath, "w", encoding="utf-8")
        self.buffer = []
        self.total_rows = 0
        # Header
        self.file.write(f"-- {table_name} data\n")

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


# ─── Data Generation ───────────────────────────────────────
class DataGenerator:
    def __init__(self):
        self.output_dir = OUTPUT_DIR
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.start_date = datetime(2024, 1, 1)
        self.end_date = datetime(2026, 2, 19)
        self.nicknames = set()

    def generate_all(self):
        print("=== CoreDisc Data Generator ===", file=sys.stderr)
        print(f"Output directory: {self.output_dir}", file=sys.stderr)
        print(file=sys.stderr)

        # Write master header
        self._write_header()

        # Generate in dependency order
        self.generate_categories()
        self.generate_members()
        self.generate_profile_imgs()
        self.generate_member_terms()
        self.generate_notification_reminder_settings()
        self.generate_official_questions()
        self.generate_personal_questions()
        self.generate_question_categories()
        self.generate_posts()
        self.generate_post_answers()
        self.generate_comments()
        self.generate_follows()
        self.generate_post_likes()
        self.generate_notifications()
        self.generate_notification_reads()
        self.generate_discs()
        self.generate_search_history()
        self.generate_devices()
        self.generate_today_questions()

        # Write loader script
        self._write_loader()

        # Summary
        total_size = sum(
            f.stat().st_size for f in self.output_dir.glob("*.sql")
        ) / (1024 * 1024)
        print(f"\nTotal SQL size: {total_size:.0f} MB", file=sys.stderr)
        print(f"\nRun: bash {self.output_dir}/load.sh", file=sys.stderr)

    def _write_header(self):
        header_path = self.output_dir / "00_header.sql"
        with open(header_path, "w") as f:
            f.write("SET FOREIGN_KEY_CHECKS = 0;\n")
            f.write("SET UNIQUE_CHECKS = 0;\n")
            f.write("SET AUTOCOMMIT = 0;\n")
            f.write("SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO';\n\n")
            # Truncate all tables
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
            f.write("# Load seed data into MySQL\n")
            f.write("set -e\n\n")
            f.write('MYSQL="/usr/local/mysql/bin/mysql"\n')
            f.write('DB_USER="root"\n')
            f.write('DB_PASS="kim980618"\n')
            f.write('DB_NAME="coredisc"\n')
            f.write(f'SQL_DIR="{self.output_dir}"\n\n')
            f.write('echo "Loading seed data into $DB_NAME..."\n')
            f.write('START=$(date +%s)\n\n')
            for sql_file in sql_files:
                if sql_file.name == "load.sh":
                    continue
                f.write(f'echo "  Loading {sql_file.name}..."\n')
                f.write(f'$MYSQL -u $DB_USER -p$DB_PASS $DB_NAME < "$SQL_DIR/{sql_file.name}"\n\n')
            # Footer
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

    # ─── Individual table generators ───────────────────────

    def generate_categories(self):
        print("[1/19] Generating categories...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "01_category.sql", "category",
            ["id", "name", "created_at", "updated_at"]
        )
        now = sql_ts(datetime.now())
        for i in range(1, NUM_CATEGORIES + 1):
            w.add_row([i, escape_sql(CATEGORY_NAMES[i - 1]), now, now])
        w.close()

    def generate_members(self):
        print("[2/19] Generating members...", file=sys.stderr)
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
                1,  # status = active
                0,  # is_social_login
                ts, ts,
            ])
            progress(i, NUM_MEMBERS, "members")
        w.close()

    def generate_profile_imgs(self):
        print("[3/19] Generating profile images...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "03_profile_img.sql", "profile_img",
            ["id", "img_url", "member_id", "created_at", "updated_at"]
        )
        now = sql_ts(datetime.now())
        # Default profile image (id=1, no member)
        w.add_row([1, escape_sql("https://local-stub/default-profile.png"), "NULL", now, now])
        # One per member
        for i in range(1, NUM_MEMBERS + 1):
            w.add_row([
                i + 1,
                escape_sql(f"https://local-stub/profiles/user{i}.png"),
                i,
                now, now,
            ])
            progress(i, NUM_MEMBERS, "profile_imgs")
        w.close()

    def generate_member_terms(self):
        print("[4/19] Generating member terms...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "04_member_terms.sql", "member_terms",
            ["id", "member_id", "terms_id", "created_at", "updated_at"]
        )
        now = sql_ts(datetime.now())
        idx = 1
        for member_id in range(1, NUM_MEMBERS + 1):
            for terms_id in [1, 2, 3]:  # Required terms
                w.add_row([idx, member_id, terms_id, now, now])
                idx += 1
            progress(member_id, NUM_MEMBERS, "member_terms")
        w.close()

    def generate_notification_reminder_settings(self):
        print("[5/19] Generating notification reminder settings...", file=sys.stderr)
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
        print("[6/19] Generating official questions...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "06_official_question.sql", "official_question",
            ["id", "is_shared", "contents", "member_id", "created_at", "updated_at"]
        )
        for i in range(1, NUM_OFFICIAL_QUESTIONS + 1):
            member_id = random.randint(1, NUM_MEMBERS)
            ts = sql_ts(random_ts(self.start_date, self.end_date))
            content = random.choice(KOREAN_QUESTIONS)
            # Pad content for data volume
            suffix = f" ({i})"
            w.add_row([
                i, 1, escape_sql(content + suffix), member_id, ts, ts,
            ])
            progress(i, NUM_OFFICIAL_QUESTIONS, "official_questions")
        w.close()

    def generate_personal_questions(self):
        print("[7/19] Generating personal questions...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "07_personal_question.sql", "personal_question",
            ["id", "content", "member_id", "created_at", "updated_at"]
        )
        for i in range(1, NUM_PERSONAL_QUESTIONS + 1):
            member_id = random.randint(1, NUM_MEMBERS)
            ts = sql_ts(random_ts(self.start_date, self.end_date))
            content = random.choice(KOREAN_QUESTIONS) + f" (개인 {i})"
            w.add_row([
                i, escape_sql(content), member_id, ts, ts,
            ])
            progress(i, NUM_PERSONAL_QUESTIONS, "personal_questions")
        w.close()

    def generate_question_categories(self):
        print("[8/19] Generating question categories...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "08_question_category.sql", "question_category",
            ["id", "category_id", "official_question_id", "personal_question_id",
             "created_at", "updated_at"]
        )
        now = sql_ts(datetime.now())
        idx = 1
        # Official questions
        for q_id in range(1, NUM_OFFICIAL_QUESTIONS + 1):
            cat_id = (q_id % NUM_CATEGORIES) + 1
            w.add_row([idx, cat_id, q_id, "NULL", now, now])
            idx += 1
            if q_id % 10000 == 0:
                progress(q_id, NUM_OFFICIAL_QUESTIONS + NUM_PERSONAL_QUESTIONS, "question_categories")
        # Personal questions
        for q_id in range(1, NUM_PERSONAL_QUESTIONS + 1):
            cat_id = (q_id % NUM_CATEGORIES) + 1
            w.add_row([idx, cat_id, "NULL", q_id, now, now])
            idx += 1
        progress(NUM_OFFICIAL_QUESTIONS + NUM_PERSONAL_QUESTIONS,
                 NUM_OFFICIAL_QUESTIONS + NUM_PERSONAL_QUESTIONS, "question_categories")
        w.close()

    def generate_posts(self):
        print("[9/19] Generating posts...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "09_post.sql", "post",
            ["id", "publicity", "status", "daily_who", "daily_where", "daily_what",
             "daily_detail", "like_count", "comment_count", "view_count",
             "member_id", "created_at", "updated_at"]
        )
        for i in range(1, NUM_POSTS + 1):
            member_id = ((i - 1) % NUM_MEMBERS) + 1
            ts = sql_ts(random_ts(self.start_date, self.end_date))
            publicity = random.choice(PUBLICITY_TYPES)
            who = random.choice(DIARY_WHO)
            where = random.choice(DIARY_WHERE)
            what = random.choice(DIARY_WHAT)
            detail = random.choice(KOREAN_SENTENCES)[:200]
            like_count = random.randint(0, 50)
            comment_count = random.randint(0, 20)
            view_count = random.randint(0, 200)
            w.add_row([
                i, escape_sql(publicity), escape_sql("PUBLISHED"),
                escape_sql(who), escape_sql(where), escape_sql(what),
                escape_sql(detail), like_count, comment_count, view_count,
                member_id, ts, ts,
            ])
            progress(i, NUM_POSTS, "posts")
        w.close()

    def generate_post_answers(self):
        print("[10/19] Generating post answers + images...", file=sys.stderr)
        w_answer = SqlWriter(
            self.output_dir / "10_post_answer.sql", "post_answer",
            ["id", "answer_order", "type", "text_content", "post_id",
             "created_at", "updated_at"]
        )
        w_image = SqlWriter(
            self.output_dir / "11_post_answer_image.sql", "post_answer_image",
            ["id", "post_answer_id", "img_url", "thumbnail_url", "s3_key",
             "original_file_name", "created_at", "updated_at"]
        )
        answer_id = 1
        image_id = 1
        for post_id in range(1, NUM_POSTS + 1):
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
                    # Image record
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
            progress(post_id, NUM_POSTS, "post_answers")
        w_answer.close()
        w_image.close()

    def generate_comments(self):
        print("[11/19] Generating comments...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "12_comment.sql", "comment",
            ["id", "content", "is_deleted", "depth", "post_id", "member_id",
             "parent_id", "created_at", "updated_at"]
        )
        # Pre-allocate: 70% top-level, 30% replies
        num_top = int(NUM_COMMENTS * 0.7)
        for i in range(1, NUM_COMMENTS + 1):
            post_id = random.randint(1, NUM_POSTS)
            member_id = random.randint(1, NUM_MEMBERS)
            ts = sql_ts(random_ts(self.start_date, self.end_date))
            # Generate longer content for data volume (~300-500 chars)
            content = random_korean_text(200, 500)
            if i <= num_top:
                # Top-level comment
                w.add_row([
                    i, escape_sql(content), 0, 0,
                    post_id, member_id, "NULL", ts, ts,
                ])
            else:
                # Reply to a random top-level comment
                parent_id = random.randint(1, num_top)
                w.add_row([
                    i, escape_sql(content), 0, 1,
                    post_id, member_id, parent_id, ts, ts,
                ])
            progress(i, NUM_COMMENTS, "comments")
        w.close()

    def generate_follows(self):
        print("[12/19] Generating follows...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "13_follow.sql", "follow",
            ["id", "follower_id", "following_id", "is_circle", "created_at", "updated_at"]
        )
        seen = set()
        idx = 1
        attempts = 0
        while idx <= NUM_FOLLOWS and attempts < NUM_FOLLOWS * 3:
            attempts += 1
            follower = random.randint(1, NUM_MEMBERS)
            following = random.randint(1, NUM_MEMBERS)
            if follower == following or (follower, following) in seen:
                continue
            seen.add((follower, following))
            ts = sql_ts(random_ts(self.start_date, self.end_date))
            is_circle = 1 if random.random() < 0.15 else 0
            w.add_row([idx, follower, following, is_circle, ts, ts])
            if idx % 10000 == 0:
                progress(idx, NUM_FOLLOWS, "follows")
            idx += 1
        progress(idx - 1, NUM_FOLLOWS, "follows")
        w.close()

    def generate_post_likes(self):
        print("[13/19] Generating post likes...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "14_post_like.sql", "post_like",
            ["id", "member_id", "post_id", "created_at", "updated_at"]
        )
        seen = set()
        idx = 1
        attempts = 0
        while idx <= NUM_POST_LIKES and attempts < NUM_POST_LIKES * 3:
            attempts += 1
            member_id = random.randint(1, NUM_MEMBERS)
            post_id = random.randint(1, NUM_POSTS)
            if (post_id, member_id) in seen:
                continue
            seen.add((post_id, member_id))
            ts = sql_ts(random_ts(self.start_date, self.end_date))
            w.add_row([idx, member_id, post_id, ts, ts])
            if idx % 10000 == 0:
                progress(idx, NUM_POST_LIKES, "post_likes")
            idx += 1
        progress(idx - 1, NUM_POST_LIKES, "post_likes")
        w.close()

    def generate_notifications(self):
        print("[14/19] Generating notifications...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "15_notification.sql", "notification",
            ["id", "receiver_id", "sender_id", "type", "content",
             "target_id", "created_at", "updated_at"]
        )
        for i in range(1, NUM_NOTIFICATIONS + 1):
            receiver_id = random.randint(1, NUM_MEMBERS)
            sender_id = random.randint(1, NUM_MEMBERS)
            noti_type = random.choice(NOTIFICATION_TYPES)
            ts = sql_ts(random_ts(self.start_date, self.end_date))

            # Generate content based on type for realism
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

            target_id = random.randint(1, NUM_POSTS) if noti_type in ("LIKE", "COMMENT", "COMMENT_REPLY") else "NULL"

            w.add_row([
                i, receiver_id, sender_id, escape_sql(noti_type),
                escape_sql(content), target_id, ts, ts,
            ])
            progress(i, NUM_NOTIFICATIONS, "notifications")
        w.close()

    def generate_notification_reads(self):
        print("[15/19] Generating notification reads...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "16_notification_read.sql", "notification_read",
            ["id", "notification_id", "member_id", "is_read"]
        )
        for i in range(1, NUM_NOTIFICATIONS + 1):
            # Roughly map notifications to their receivers
            receiver_id = ((i - 1) % NUM_MEMBERS) + 1
            is_read = 1 if random.random() < 0.7 else 0
            w.add_row([i, i, receiver_id, is_read])
            progress(i, NUM_NOTIFICATIONS, "notification_reads")
        w.close()

    def generate_discs(self):
        print("[16/19] Generating discs...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "17_disc.sql", "disc",
            ["id", "year", "month", "cover_color", "cover_img_url",
             "member_id", "created_at", "updated_at"]
        )
        now = sql_ts(datetime.now())
        idx = 1
        seen = set()
        # Generate discs for members across months
        for member_id in range(1, NUM_MEMBERS + 1):
            # Each member has 1-24 months of discs
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
                w.add_row([
                    idx, year, month, escape_sql(color), "NULL",
                    member_id, now, now,
                ])
                idx += 1
                if idx > NUM_DISCS:
                    break
            if idx > NUM_DISCS:
                break
            progress(min(idx, NUM_DISCS), NUM_DISCS, "discs")
        progress(min(idx - 1, NUM_DISCS), NUM_DISCS, "discs")
        w.close()

    def generate_search_history(self):
        print("[17/19] Generating search history...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "18_search_history.sql", "search_history",
            ["id", "keyword", "search_type", "searched_at", "member_id",
             "created_at", "updated_at"]
        )
        for i in range(1, NUM_SEARCH_HISTORY + 1):
            member_id = random.randint(1, NUM_MEMBERS)
            keyword = random.choice(SEARCH_KEYWORDS) + str(random.randint(1, 100))
            ts = sql_ts(random_ts(self.start_date, self.end_date))
            w.add_row([
                i, escape_sql(keyword), escape_sql("MEMBER"), ts, member_id, ts, ts,
            ])
            progress(i, NUM_SEARCH_HISTORY, "search_history")
        w.close()

    def generate_devices(self):
        print("[18/19] Generating devices...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "19_device.sql", "device",
            ["id", "member_id", "token", "device_type", "is_active",
             "created_at", "updated_at"]
        )
        now = sql_ts(datetime.now())
        for i in range(1, NUM_MEMBERS + 1):
            token = f"fcm_token_{''.join(random.choices(string.ascii_letters + string.digits, k=140))}"
            w.add_row([
                i, i, escape_sql(token), escape_sql("iOS"), 1, now, now,
            ])
            progress(i, NUM_MEMBERS, "devices")
        w.close()

    def generate_today_questions(self):
        print("[19/19] Generating today questions...", file=sys.stderr)
        w = SqlWriter(
            self.output_dir / "20_today_question.sql", "today_question",
            ["id", "selected_date", "question_type", "question_order",
             "member_id", "official_question_id", "personal_question_id",
             "created_at", "updated_at"]
        )
        idx = 1
        # Each member gets questions for multiple days
        for member_id in range(1, min(NUM_MEMBERS + 1, 5001)):
            num_days = random.randint(30, 200)
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
            progress(member_id, 5000, "today_questions")
        w.close()


# ─── Main ──────────────────────────────────────────────────
if __name__ == "__main__":
    random.seed(42)  # Reproducible
    gen = DataGenerator()
    gen.generate_all()
