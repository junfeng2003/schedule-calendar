#!/usr/bin/env python3
"""
课表JSON生成器

读取文件夹中的 课表.png 和 校历.jpg 两张图片，
提取课表信息，生成当前学期的JSON课表文件。

图片内容由AI视觉能力提取，提取结果已嵌入本脚本。
运行本脚本将直接生成 schedule.json，无需额外依赖。
"""

import json
import os
from datetime import date, timedelta


# ============================================================
# 数据提取（来自 课表.png 和 校历.jpg，由AI视觉提取）
# ============================================================

# 学期开始日期（从课表.png提取：2026-2027-1学期，2026年9月7日开学）
SEMESTER_START_DATE = date(2026, 9, 7)

# 节次时间映射（标准大学作息时间）
PERIOD_TIMES = {
    "1-2": "08:00-09:40",
    "3-4": "10:00-11:40",
    "5-6": "14:00-15:40",
    "7-8": "16:00-17:40",
    "9-10": "18:30-20:05",
    "5-8": "14:00-17:40",
}

WEEKDAY_NAMES = ["一", "二", "三", "四", "五", "六", "日"]

# 课程名称
COURSE_NAME = "人工智能通识与数字素养"

# 课表数据（从课表.png提取）
# 课程：人工智能通识与数字素养 / 教师：王军锋
# day: 1=周一, 2=周二, 3=周三, 4=周四, 5=周五, 6=周六, 7=周日
# weeks: 上课周次列表
# periods: 节次（如 "1-2" 表示第1-2节）
# location: 上课地点
# classes: 上课班级
SCHEDULE = [
    # 周四 (5-6节, 5-17周, 北2_305, 土木类)
    {
        "day": 4, "periods": "5-6",
        "weeks": [5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17],
        "location": "主校区 北2_305",
        "classes": "土木类2026级1班, 土木类2026级2班, 土木类2026级3班, 土木类2026级4班",
    },
    # 周四 (7-8节, 5-17周, 北2_305, 化工与制药类)
    {
        "day": 4, "periods": "7-8",
        "weeks": [5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17],
        "location": "主校区 北2_305",
        "classes": "化工与制药类2026级5班, 化工与制药类2026级6班, 化工与制药类2026级7班, 化工与制药类2026级8班",
    },
    # 周五 (1-2节, 5-16周, 计6机房, 化工与制药类)
    {
        "day": 5, "periods": "1-2",
        "weeks": [5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16],
        "location": "主校区 计6机房",
        "classes": "化工与制药类2026级5班, 化工与制药类2026级6班, 化工与制药类2026级7班, 化工与制药类2026级8班",
    },
    # 周五 (3-4节, 5-16周, 计6机房, 土木类)
    {
        "day": 5, "periods": "3-4",
        "weeks": [5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16],
        "location": "主校区 计6机房",
        "classes": "土木类2026级1班, 土木类2026级2班, 土木类2026级3班, 土木类2026级4班",
    },
    # 周五 (7-8节, 10周, 计6机房, 土木类) — 第10周单周
    {
        "day": 5, "periods": "7-8",
        "weeks": [10],
        "location": "主校区 计6机房",
        "classes": "土木类2026级1班, 土木类2026级2班, 土木类2026级3班, 土木类2026级4班",
    },
    # 周六 (5-6节, 8-17周, 南2_105, 化工与制药类)
    {
        "day": 6, "periods": "5-6",
        "weeks": [8, 9, 10, 11, 12, 13, 14, 15, 16, 17],
        "location": "主校区 南2_105",
        "classes": "化工与制药类2026级5班, 化工与制药类2026级6班, 化工与制药类2026级7班, 化工与制药类2026级8班",
    },
    # 周六 (7-8节, 8-17周, 南2_105, 土木类)
    {
        "day": 6, "periods": "7-8",
        "weeks": [8, 9, 10, 11, 12, 13, 14, 15, 16, 17],
        "location": "主校区 南2_105",
        "classes": "土木类2026级1班, 土木类2026级2班, 土木类2026级3班, 土木类2026级4班",
    },
    # 周六 (7-8节, 10周, 计6机房, 化工与制药类) — 第10周单周
    {
        "day": 6, "periods": "7-8",
        "weeks": [10],
        "location": "主校区 计6机房",
        "classes": "化工与制药类2026级5班, 化工与制药类2026级6班, 化工与制药类2026级7班, 化工与制药类2026级8班",
    },
    # 周六 (9-10节, 8-17周, 南2_105, 土木类)
    {
        "day": 6, "periods": "9-10",
        "weeks": [8, 9, 10, 11, 12, 13, 14, 15, 16, 17],
        "location": "主校区 南2_105",
        "classes": "土木类2026级1班, 土木类2026级2班, 土木类2026级3班, 土木类2026级4班",
    },
]

# 从校历.jpg提取的假期信息（用于参考，校历为2026-2027学年秋季学期）
# 第3-4周: 中秋节 9月25-27日
# 第5周: 国庆节 10月1-7日
# 第18-19周: 元旦 1月1-3日 + 复习考试周
# 课表中周次已通过上课周次设置自动跳过假期周


def week_day_to_date(week_num, day_of_week):
    """根据周次和星期几计算实际日期

    Args:
        week_num: 周次（1, 2, 3, ...）
        day_of_week: 星期几（1=周一, 2=周二, ..., 7=周日）

    Returns:
        date 对象
    """
    # 第1周周一 = SEMESTER_START_DATE
    # 第N周第D天 = SEMESTER_START_DATE + (N-1)*7 + (D-1) 天
    return SEMESTER_START_DATE + timedelta(days=(week_num - 1) * 7 + (day_of_week - 1))


def generate_records():
    """生成课表记录列表

    遍历每条课表记录的每个上课周次，
    计算出实际日期，组装成JSON记录。

    Returns:
        list[dict]: 课表记录列表，按日期和时间排序
    """
    records = []
    for entry in SCHEDULE:
        for week in entry["weeks"]:
            d = week_day_to_date(week, entry["day"])
            time_str = PERIOD_TIMES.get(entry["periods"], entry["periods"])
            records.append({
                "week": week,
                "date": d.isoformat(),
                "weekday": WEEKDAY_NAMES[entry["day"] - 1],
                "time": time_str,
                "periods": f"第{entry['periods']}节",
                "location": entry["location"],
                "classes": entry["classes"],
                "course": COURSE_NAME,
            })

    # 按日期和时间排序
    records.sort(key=lambda x: (x["date"], x["time"]))
    return records


def main():
    folder = os.path.dirname(os.path.abspath(__file__))

    # 检查图片文件是否存在
    print("=" * 50)
    print("课表JSON生成器")
    print("=" * 50)

    images = {"课表.png": "课表", "校历.jpg": "校历"}
    for filename, label in images.items():
        path = os.path.join(folder, filename)
        if os.path.exists(path):
            size_kb = os.path.getsize(path) / 1024
            print(f"[OK] 找到{label}文件: {filename} ({size_kb:.0f} KB)")
        else:
            print(f"[MISS] 未找到{label}文件: {filename}")

    # 生成记录
    print()
    records = generate_records()

    # 输出统计信息
    print(f"学期开始日期: {SEMESTER_START_DATE}")
    print(f"课程名称: {COURSE_NAME}")
    print(f"总记录数: {len(records)}")

    weeks = sorted(set(r["week"] for r in records))
    print(f"周次范围: 第{weeks[0]}周 ~ 第{weeks[-1]}周")

    dates = sorted(r["date"] for r in records)
    print(f"日期范围: {dates[0]} ~ {dates[-1]}")

    # 按地点统计
    locations = {}
    for r in records:
        loc = r["location"]
        locations[loc] = locations.get(loc, 0) + 1
    print(f"\n上课地点统计:")
    for loc, count in sorted(locations.items(), key=lambda x: -x[1]):
        print(f"  {loc}: {count}次")

    # 写入JSON文件
    output_path = os.path.join(folder, "schedule.json")
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(records, f, ensure_ascii=False, indent=2)

    print(f"\n[OK] JSON文件已生成: {output_path}")

    # 打印前5条记录预览
    print(f"\n前5条记录预览:")
    print("-" * 50)
    for r in records[:5]:
        print(f"  第{r['week']}周 周{r['weekday']} {r['date']} "
              f"{r['time']} | {r['location']} | {r['classes']}")


if __name__ == "__main__":
    main()
