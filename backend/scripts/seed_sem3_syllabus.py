#!/usr/bin/env python3
"""
Seed script for UTU B.Tech CSE (Cyber Security) Semester III Syllabus.
Populates courses, course_units, and unit_topics.
Safe to run multiple times (idempotent upserts).
"""

import argparse
import asyncio
import sys
import uuid
from typing import List, Dict, Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

# Import models
from app.database import clean_asyncpg_url
from app.config import settings
from app.models.attendance import Base, Course, CourseUnit, UnitTopic


SEMESTER_3_DATA: List[Dict[str, Any]] = [
    {
        "code": "AHT-006",
        "name": "Advanced Applied Mathematics",
        "category": "BSC",
        "course_type": "THEORY",
        "credits": 4, "l": 3, "t": 1, "p": 0,
        "units": [
            {
                "num": 1, "title": "Laplace Transform", "hours": 8,
                "topics": [
                    "Laplace Transform Definition and Existence Theorems",
                    "Transforms of Derivatives and Integrals",
                    "Unit Step and Dirac-Delta Functions",
                    "Inverse Laplace Transform and Convolution Theorem",
                    "Application to Linear Differential Equations"
                ]
            },
            {
                "num": 2, "title": "Fourier Transforms", "hours": 8,
                "topics": [
                    "Fourier Integral and Complex Form",
                    "Fourier Sine and Cosine Transforms",
                    "Convolution Theorem for Fourier Transforms",
                    "Applications to One-Dimensional Heat Equations"
                ]
            },
            {
                "num": 3, "title": "Algebraic & Transcendental Equations and Interpolation", "hours": 8,
                "topics": [
                    "Roots of Equations: Bisection, Iteration, Newton-Raphson, Regula-Falsi",
                    "Finite Differences and Operator Relations",
                    "Newton Forward and Backward Interpolation",
                    "Newton Divided Difference and Lagrange Interpolation"
                ]
            },
            {
                "num": 4, "title": "Numerical Differentiation, Integration and ODE", "hours": 8,
                "topics": [
                    "Numerical Differentiation Techniques",
                    "Numerical Integration: Trapezoidal, Simpson 1/3 and 3/8 Rules",
                    "Runge-Kutta 4th Order Method for ODE",
                    "Milne Predictor-Corrector Method"
                ]
            },
            {
                "num": 5, "title": "Statistical Techniques", "hours": 8,
                "topics": [
                    "Measures of Central Tendency, Moments, Skewness, Kurtosis",
                    "Curve Fitting and Method of Least Squares",
                    "Correlation and Rank Correlation",
                    "Linear and Non-Linear Regression Analysis"
                ]
            }
        ]
    },
    {
        "code": "ECT-033",
        "name": "Digital Electronics",
        "category": "ESC",
        "course_type": "THEORY",
        "credits": 4, "l": 3, "t": 1, "p": 0,
        "units": [
            {
                "num": 1, "title": "Minimization of Logic Functions", "hours": 8,
                "topics": [
                    "Logic Gates and Boolean Algebra Review",
                    "SOP, POS, and Canonical Forms",
                    "K-Maps and Quine-McCluskey Minimization",
                    "Binary Codes and Code Conversion"
                ]
            },
            {
                "num": 2, "title": "Combinational Circuits", "hours": 8,
                "topics": [
                    "Adders, Subtractors, and ALU Design",
                    "Code Converters and Comparators",
                    "Decoders, Encoders, Multiplexers, and Demultiplexers",
                    "Parity Checkers and Display Drivers"
                ]
            },
            {
                "num": 3, "title": "Sequential Circuits", "hours": 8,
                "topics": [
                    "Latches and Flip-Flops (SR, JK, Master-Slave, Edge-Triggered)",
                    "Counters (Ripple and Synchronous)",
                    "Shift Registers and Universal Shift Registers",
                    "Finite State Machines and Synchronous Sequential Design"
                ]
            },
            {
                "num": 4, "title": "Logic Families and Semiconductor Memories", "hours": 8,
                "topics": [
                    "Logic Families (TTL, ECL, CMOS) and Interfacing",
                    "Memory Elements and Semiconductor Memories",
                    "Programmable Logic Devices (PLDs and FPGAs)"
                ]
            },
            {
                "num": 5, "title": "VLSI Design Flow", "hours": 8,
                "topics": [
                    "VLSI Design Entry: Schematic, FSM, and HDL",
                    "VHDL Modeling Styles (Dataflow, Behavioral, Structural)",
                    "VHDL Synthesis and Simulation for Digital Circuits"
                ]
            }
        ]
    },
    {
        "code": "AHT-007",
        "name": "Technical Communication",
        "category": "HSC",
        "course_type": "THEORY",
        "credits": 3, "l": 2, "t": 1, "p": 0,
        "units": [
            {
                "num": 1, "title": "Fundamentals of Technical Communication", "hours": 6,
                "topics": [
                    "Introduction and Features of Technical Communication",
                    "Distinction Between General and Technical Communication",
                    "Flow of Communication and Communication Barriers"
                ]
            },
            {
                "num": 2, "title": "Forms of Technical Communication", "hours": 6,
                "topics": [
                    "Technical Reports, Theses, and Synopsis Writing",
                    "Technical Research Papers, Seminars, and Conferences",
                    "7 Cs of Effective Business Writing"
                ]
            },
            {
                "num": 3, "title": "Technical Presentation Strategies and Techniques", "hours": 6,
                "topics": [
                    "Presentation Forms and Delivery Modes",
                    "Public Speaking and Overcoming Stage Fear",
                    "Audience Analysis and Interactive Quizzing"
                ]
            },
            {
                "num": 4, "title": "Technical Communication Skills", "hours": 6,
                "topics": [
                    "Interview Skills and Group Discussions",
                    "Argumentation, Analysis, and Critical Thinking",
                    "Exposition, Narration, and Description Nuances"
                ]
            },
            {
                "num": 5, "title": "Kinesics and Voice Dynamics", "hours": 6,
                "topics": [
                    "Kinesics and Body Language Principles",
                    "Voice Modulation: Quality, Pitch, and Rhythm",
                    "Phonetics, Intonation, and Articulation"
                ]
            }
        ]
    },
    {
        "code": "AHT-008",
        "name": "Universal Human Values",
        "category": "HSC",
        "course_type": "THEORY",
        "credits": 3, "l": 2, "t": 1, "p": 0,
        "units": [
            {
                "num": 1, "title": "Introduction to Value Education", "hours": 6,
                "topics": [
                    "Universal Human Values and Self-Exploration",
                    "Continuous Happiness and Prosperity as Human Aspirations",
                    "Right Understanding, Relationships, and Physical Facilities"
                ]
            },
            {
                "num": 2, "title": "Harmony in the Human Being", "hours": 6,
                "topics": [
                    "Understanding Self (I) and Body Distinction",
                    "Characteristics, Activities, and Harmony within I",
                    "Harmony with the Body: Sanyam and Health"
                ]
            },
            {
                "num": 3, "title": "Harmony in the Family and Society", "hours": 6,
                "topics": [
                    "Values in Relationships: Trust and Respect",
                    "Intention vs. Competence in Human Interactions",
                    "Universal Harmonious Order and Society Coexistence"
                ]
            },
            {
                "num": 4, "title": "Harmony in Nature and Existence", "hours": 5,
                "topics": [
                    "Four Orders in Nature and Interconnectedness",
                    "Existence as Coexistence at All Levels"
                ]
            },
            {
                "num": 5, "title": "Harmony in Professional Ethics", "hours": 5,
                "topics": [
                    "Natural Acceptance of Human Values and Ethical Conduct",
                    "Competence in Professional Ethics and Case Studies",
                    "Transition to Universal Human Order"
                ]
            }
        ]
    },
    {
        "code": "CST-002",
        "name": "Discrete Structure",
        "category": "DC",
        "course_type": "THEORY",
        "credits": 4, "l": 3, "t": 1, "p": 0,
        "units": [
            {
                "num": 1, "title": "Set Theory and Functions", "hours": 8,
                "topics": [
                    "Sets, Set Operations, Power Sets, and Cartesian Products",
                    "Relations, Equivalence Relations, and Partitions",
                    "Mathematical Induction",
                    "Functions: Injective, Surjective, Bijective, and Inverses"
                ]
            },
            {
                "num": 2, "title": "Propositional Calculus and Lattices", "hours": 8,
                "topics": [
                    "Propositions, Truth Tables, Tautologies, and Contradictions",
                    "Formal Logic and Inference Rules",
                    "Partial Orderings and Posets",
                    "Lattice Theory and Special Lattices"
                ]
            },
            {
                "num": 3, "title": "Combinatorics and Recurrence Relations", "hours": 8,
                "topics": [
                    "Counting Principles, Permutations, and Combinations",
                    "Pigeonhole Principle and Inclusion-Exclusion",
                    "Linear Recurrence Relations with Constant Coefficients",
                    "Generating Functions for Recurrence Solutions"
                ]
            },
            {
                "num": 4, "title": "Algebraic Structures", "hours": 8,
                "topics": [
                    "Semigroups, Monoids, and Groups",
                    "Subgroups, Normal Subgroups, and Cyclic Groups",
                    "Homomorphism, Isomorphism, and Automorphism",
                    "Rings, Integral Domains, and Fields"
                ]
            },
            {
                "num": 5, "title": "Graphs and Trees", "hours": 8,
                "topics": [
                    "Graph Terminology, Directed and Undirected Graphs",
                    "Paths, Circuits, Eulerian and Hamiltonian Graphs",
                    "Planar Graphs and Euler Formula",
                    "Trees, Spanning Trees, and Binary Tree Traversals"
                ]
            }
        ]
    },
    {
        "code": "CST-003",
        "name": "Data Structures and Algorithms",
        "category": "DC",
        "course_type": "THEORY",
        "credits": 4, "l": 3, "t": 1, "p": 0,
        "units": [
            {
                "num": 1, "title": "Introduction & Algorithm Analysis", "hours": 8,
                "topics": [
                    "Data Structure Operations and Asymptotic Notations",
                    "Time-Space Trade-off Analysis",
                    "Linear Search and Binary Search Analysis"
                ]
            },
            {
                "num": 2, "title": "Stacks and Queues", "hours": 8,
                "topics": [
                    "Stack ADT and Stack Operations",
                    "Expression Conversion (Infix, Prefix, Postfix) and Evaluation",
                    "Queue ADT: Simple, Circular, and Priority Queues"
                ]
            },
            {
                "num": 3, "title": "Linked Lists", "hours": 8,
                "topics": [
                    "Singly Linked List Operations and Complexity",
                    "Doubly Linked List and Circular Linked List",
                    "Linked Representation of Stacks and Queues"
                ]
            },
            {
                "num": 4, "title": "Trees and Graphs", "hours": 8,
                "topics": [
                    "Binary Trees and Binary Search Trees (BST)",
                    "Balanced Trees: AVL Trees, B-Trees, and B+ Trees",
                    "Graph Representations and Traversals (BFS and DFS)"
                ]
            },
            {
                "num": 5, "title": "Sorting and Hashing", "hours": 8,
                "topics": [
                    "Comparison Sorts: Selection, Bubble, and Insertion Sort",
                    "Divide & Conquer Sorts: Quick Sort, Merge Sort, and Heap Sort",
                    "Hashing, Hash Functions, and Collision Resolution Techniques"
                ]
            }
        ]
    },
    {
        "code": "CST-004",
        "name": "Object Oriented Programming",
        "category": "DC",
        "course_type": "THEORY",
        "credits": 4, "l": 3, "t": 1, "p": 0,
        "units": [
            {
                "num": 1, "title": "OOP Concepts & C++ Basics", "hours": 8,
                "topics": [
                    "Classes, Objects, Abstraction, and Encapsulation",
                    "Constructors, Destructors, and Static Class Members",
                    "Friend Functions, Friend Classes, and Inline Functions",
                    "Pointers, References, and Dynamic Memory Allocation"
                ]
            },
            {
                "num": 2, "title": "Function Overloading and Operator Overloading", "hours": 8,
                "topics": [
                    "Function Overloading and Default Arguments",
                    "Copy Constructors and Overloading Constructors",
                    "Operator Overloading Using Member and Friend Functions",
                    "Overloading new and delete Operators"
                ]
            },
            {
                "num": 3, "title": "Inheritance and Polymorphism", "hours": 8,
                "topics": [
                    "Types of Inheritance and Base-Class Access Control",
                    "Virtual Base Classes and Multiple Inheritance",
                    "Virtual Functions and Runtime Polymorphism",
                    "RTTI and Type Casting Operators"
                ]
            },
            {
                "num": 4, "title": "Templates and Exception Handling", "hours": 8,
                "topics": [
                    "Generic Functions and Class Templates",
                    "Exception Handling Fundamentals: try, catch, and throw",
                    "Standard Exception Classes and Custom Handlers"
                ]
            },
            {
                "num": 5, "title": "I/O Streams & Standard Template Library (STL)", "hours": 8,
                "topics": [
                    "C++ Stream Classes and Formatted I/O",
                    "File Streams and File I/O Operations",
                    "Standard Template Library: Containers, Iterators, and Algorithms"
                ]
            }
        ]
    },
    {
        "code": "CSP-003",
        "name": "Data Structures and Algorithms Lab",
        "category": "DLC",
        "course_type": "LAB",
        "credits": 1, "l": 0, "t": 0, "p": 2,
        "units": [
            {
                "num": 1, "title": "List of Experiments", "hours": 28,
                "topics": [
                    "Stack and Queue ADT Implementation using Array",
                    "Stack and Queue Implementation using Singly Linked List",
                    "Double-Ended Queue (Deque) using Doubly Linked List",
                    "Binary Search Tree Operations (Insert, Delete, Search)",
                    "Circular Queue ADT Implementation",
                    "Dictionary ADT Implementation using Hashing",
                    "AVL Tree and B-Tree Operations",
                    "Graph Traversals: Breadth First Search (BFS) and Depth First Search (DFS)",
                    "Minimum Spanning Tree (Prims and Kruskals Algorithms)",
                    "Single Source Shortest Path (Dijkstras Algorithm)",
                    "Binary Tree Non-Recursive Traversals (Pre-order, In-order, Post-order)",
                    "Sorting Algorithms Implementation (Quick Sort and Merge Sort)"
                ]
            }
        ]
    },
    {
        "code": "CSP-004",
        "name": "Object Oriented Programming Lab",
        "category": "DLC",
        "course_type": "LAB",
        "credits": 1, "l": 0, "t": 0, "p": 2,
        "units": [
            {
                "num": 1, "title": "List of Experiments", "hours": 28,
                "topics": [
                    "Classes with Static Members and Friend Functions",
                    "Matrix Class with Dynamic Memory Allocation and Assignment Overloading",
                    "Complex Number Class with Operator Overloading and Type Conversions",
                    "Custom Dynamic Memory Allocation Overloading new and delete",
                    "Class Hierarchy for Multiple and Multilevel Inheritances",
                    "Dynamic Polymorphism and RTTI Demonstration",
                    "Linked List Class Template and Generic Algorithms",
                    "Sorting Algorithm Templates (Bubble, Insertion, Quick Sort)",
                    "Stack and Queue Classes with Exception Handling",
                    "File I/O for Persistent Object Data Processing"
                ]
            }
        ]
    },
    {
        "code": "CSP-005",
        "name": "Python Programming Lab",
        "category": "DLC",
        "course_type": "LAB",
        "credits": 1, "l": 0, "t": 0, "p": 2,
        "units": [
            {
                "num": 1, "title": "List of Experiments", "hours": 28,
                "topics": [
                    "Interactive Interpreter and Python Script Execution",
                    "Operators, Distance Calculation, and Command-Line Arguments",
                    "Conditional Statements and Loop Constructs",
                    "Prime Numbers, Fibonacci Series, and Basic Searching/Sorting",
                    "String Manipulation and Dictionary Operations",
                    "List and Dictionary Aggregation",
                    "File Reading, Writing, and Word Counting",
                    "Functions, Geometric Collisions, and Statistical Measures",
                    "String Distance, List Deduplication, and Uniqueness",
                    "Recursive Functions, Inversions, and GCD/LCM Computations",
                    "Scientific Packages: NumPy, Pandas, Matplotlib, and Requests"
                ]
            }
        ]
    },
    {
        "code": "CSP-006",
        "name": "Internship-I / Mini Project-I",
        "category": "DLC",
        "course_type": "LAB",
        "credits": 1, "l": 0, "t": 0, "p": 2,
        "units": [
            {
                "num": 1, "title": "Project Work & Assessment", "hours": 28,
                "topics": [
                    "Problem Identification and Industrial Requirement Gathering",
                    "System Architecture, Design, and Technology Stack Selection",
                    "Module Implementation, Integration, and Verification",
                    "Technical Documentation, Presentation, and Viva Voce"
                ]
            }
        ]
    },
    {
        "code": "CST-005",
        "name": "Python Programming",
        "category": "MC",
        "course_type": "THEORY",
        "credits": 0, "l": 2, "t": 0, "p": 0,
        "units": [
            {
                "num": 1, "title": "Introduction and Syntax of Python", "hours": 6,
                "topics": [
                    "Features of Python, Building Blocks, and Environment Setup",
                    "Identifiers, Keywords, Indentation, and Variables",
                    "Python Data Types: Numbers, Strings, Tuples, Lists, Dictionaries"
                ]
            },
            {
                "num": 2, "title": "Operators and Control Flow Statements", "hours": 6,
                "topics": [
                    "Arithmetic, Relational, Logical, and Bitwise Operators",
                    "Conditional Statements: if, if-else, nested if",
                    "Looping in Python: while loop, for loop, nested loops",
                    "Loop Manipulation: break, continue, pass"
                ]
            },
            {
                "num": 3, "title": "Data Structures in Python", "hours": 6,
                "topics": [
                    "String Operations, Escape Characters, and Formatting",
                    "List Operations, Slicing, and Built-in List Methods",
                    "Tuple Operations and Set Operations",
                    "Dictionary Operations and Built-in Dictionary Methods"
                ]
            },
            {
                "num": 4, "title": "Functions, Modules, and Packages", "hours": 6,
                "topics": [
                    "Built-in Functions and Type Conversion",
                    "User-Defined Functions and Parameter Passing",
                    "Scope of Variables: Global and Local Variables",
                    "Creating and Importing Python Modules and Packages"
                ]
            },
            {
                "num": 5, "title": "File Handling", "hours": 6,
                "topics": [
                    "Opening Files in Different Modes (Read, Write, Append)",
                    "Reading and Writing Files using Standard Library",
                    "Closing, Renaming, and Deleting Files"
                ]
            }
        ]
    },
    {
        "code": "CST-006",
        "name": "Cyber Security",
        "category": "MC",
        "course_type": "THEORY",
        "credits": 0, "l": 2, "t": 0, "p": 0,
        "units": [
            {
                "num": 1, "title": "Introduction to Cyber Security", "hours": 6,
                "topics": [
                    "Cyber Security Concepts, CIA Triad, and Layers of Security",
                    "Vulnerabilities, Threats, and Attack Taxonomy",
                    "Active Attacks, Passive Attacks, and IP Spoofing",
                    "Cyber Warfare, Cyber Espionage, and Risk Management"
                ]
            },
            {
                "num": 2, "title": "Cyber Forensics", "hours": 6,
                "topics": [
                    "Digital Forensics Science and Evidence Collection",
                    "The Need for Computer Forensics and Legal Evidence",
                    "Digital Forensics Lifecycle and Email Investigation",
                    "Forensic Auditing Challenges and Special Techniques"
                ]
            },
            {
                "num": 3, "title": "Cybercrime in Mobile and Wireless Devices", "hours": 6,
                "topics": [
                    "Proliferation of Mobile Devices and Security Challenges",
                    "Mobile Authentication Services and Credit Card Frauds",
                    "Attacks on Mobile Phones and Registry Settings",
                    "Organizational Security Policies for Mobile and Laptops"
                ]
            },
            {
                "num": 4, "title": "Organizational Implications and Cybercrime", "hours": 6,
                "topics": [
                    "Cost of Cybercrimes and Intellectual Property Issues in Cyberspace",
                    "Web Threats and Social Media Marketing Risks for Organizations",
                    "Psychology, Mindset, and Skills of Hackers and Cybercriminals"
                ]
            },
            {
                "num": 5, "title": "Cyberspace Law and IT Act Provisions", "hours": 6,
                "topics": [
                    "Cybersecurity Regulations and International Laws",
                    "National Cyber Security Policy and Internet Governance",
                    "Offences under the Indian Information Technology Act 2008",
                    "Admissibility and Role of Electronic Evidence"
                ]
            }
        ]
    }
]

# Prune unselected optional courses (retaining Digital Electronics, UHV, Python Programming)
SEMESTER_3_DATA = [
    c for c in SEMESTER_3_DATA
    if c["code"] not in ("AHT-006", "AHT-007", "CST-006")
]


async def seed_semester_3(db_url: str):
    clean_url = clean_asyncpg_url(db_url)
    is_postgres = clean_url.startswith("postgresql")
    connect_args = {
        "statement_cache_size": 0,
        "prepared_statement_cache_size": 0,
    } if is_postgres else ({"check_same_thread": False} if clean_url.startswith("sqlite") else {})

    print(f"Connecting to database...")
    engine = create_async_engine(clean_url, connect_args=connect_args)
    async_session = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async with async_session() as session:
        print(f"Beginning syllabus seeding for 3rd Semester ({len(SEMESTER_3_DATA)} courses)...")
        inserted_courses = 0
        inserted_units = 0
        inserted_topics = 0

        for c_data in SEMESTER_3_DATA:
            code = c_data["code"]
            # Find or create Course
            res = await session.execute(select(Course).where(Course.course_code == code))
            course = res.scalars().first()

            if not course:
                course = Course(
                    id=uuid.uuid4(),
                    course_code=code,
                    course_name=c_data["name"],
                    program="B.Tech",
                    department="Computer Science & Engineering (Cyber Security)",
                    semester=3,
                    category=c_data["category"],
                    course_type=c_data["course_type"],
                    credits=c_data["credits"],
                    periods_l=c_data["l"],
                    periods_t=c_data["t"],
                    periods_p=c_data["p"],
                )
                session.add(course)
                await session.flush()
                inserted_courses += 1
            else:
                course.course_name = c_data["name"]
                course.program = "B.Tech"
                course.department = "Computer Science & Engineering (Cyber Security)"
                course.semester = 3
                course.category = c_data["category"]
                course.course_type = c_data["course_type"]
                course.credits = c_data["credits"]
                course.periods_l = c_data["l"]
                course.periods_t = c_data["t"]
                course.periods_p = c_data["p"]

            for u_data in c_data["units"]:
                u_num = u_data["num"]
                u_res = await session.execute(
                    select(CourseUnit).where(
                        CourseUnit.course_id == course.id,
                        CourseUnit.unit_number == u_num,
                    )
                )
                unit = u_res.scalars().first()

                if not unit:
                    unit = CourseUnit(
                        id=uuid.uuid4(),
                        course_id=course.id,
                        unit_number=u_num,
                        unit_title=u_data["title"],
                        teaching_hours=u_data.get("hours"),
                    )
                    session.add(unit)
                    await session.flush()
                    inserted_units += 1
                else:
                    unit.unit_title = u_data["title"]
                    unit.teaching_hours = u_data.get("hours")

                for t_idx, topic_name in enumerate(u_data["topics"], start=1):
                    t_res = await session.execute(
                        select(UnitTopic).where(
                            UnitTopic.unit_id == unit.id,
                            UnitTopic.topic_order == t_idx,
                        )
                    )
                    topic = t_res.scalars().first()

                    if not topic:
                        topic = UnitTopic(
                            id=uuid.uuid4(),
                            unit_id=unit.id,
                            topic_order=t_idx,
                            topic_name=topic_name,
                        )
                        session.add(topic)
                        inserted_topics += 1
                    else:
                        topic.topic_name = topic_name

        await session.commit()
        print(f"SUCCESS: Seeded Semester III syllabus!")
        print(f"  Total Courses Processed: {len(SEMESTER_3_DATA)} (New: {inserted_courses})")
        print(f"  Total Units: 49 (New: {inserted_units})")
        print(f"  Total Main Topics: 195 (New: {inserted_topics})")

    await engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Seed UTU B.Tech Semester 3 Syllabus")
    parser.add_argument("--db-url", type=str, default=settings.DATABASE_URL, help="Database URL")
    args = parser.parse_args()

    asyncio.run(seed_semester_3(args.db_url))
