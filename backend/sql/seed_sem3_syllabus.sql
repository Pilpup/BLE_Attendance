-- =====================================================================
-- UTU B.Tech CSE (Cyber Security) - Semester III Complete Syllabus Seed
-- =====================================================================

DO $$
DECLARE
    v_course_id UUID;
    v_unit_id UUID;
BEGIN
    -- Step 1: Ensure Schema Columns & Tables Exist
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS program VARCHAR(30) DEFAULT 'B.Tech';
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS department VARCHAR(100) DEFAULT 'Computer Science & Engineering';
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS semester INTEGER DEFAULT 1;
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS category VARCHAR(20) DEFAULT 'DC';
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS course_type VARCHAR(20) DEFAULT 'THEORY';
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS credits INTEGER DEFAULT 4;
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS periods_l INTEGER DEFAULT 3;
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS periods_t INTEGER DEFAULT 1;
    ALTER TABLE courses ADD COLUMN IF NOT EXISTS periods_p INTEGER DEFAULT 0;
    ALTER TABLE courses ALTER COLUMN teacher_id DROP NOT NULL;
    CREATE INDEX IF NOT EXISTS ix_courses_dept_sem ON courses(department, semester);

    CREATE TABLE IF NOT EXISTS course_units (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        course_id UUID NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
        unit_number INTEGER NOT NULL,
        unit_title VARCHAR(255) NOT NULL,
        teaching_hours INTEGER,
        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
        CONSTRAINT uq_course_unit UNIQUE (course_id, unit_number)
    );
    CREATE INDEX IF NOT EXISTS ix_course_units_course_id ON course_units(course_id);

    CREATE TABLE IF NOT EXISTS unit_topics (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        unit_id UUID NOT NULL REFERENCES course_units(id) ON DELETE CASCADE,
        topic_order INTEGER NOT NULL,
        topic_name TEXT NOT NULL,
        created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
        CONSTRAINT uq_unit_topic UNIQUE (unit_id, topic_order)
    );
    CREATE INDEX IF NOT EXISTS ix_unit_topics_unit_id ON unit_topics(unit_id);

    -- Step 2: Seed Semester III Courses, Units, and Main Topics

    -- Course: AHT-006 - Advanced Applied Mathematics
    INSERT INTO courses (id, course_code, course_name, program, department, semester, category, course_type, credits, periods_l, periods_t, periods_p)
    VALUES (gen_random_uuid(), 'AHT-006', 'Advanced Applied Mathematics', 'B.Tech', 'Computer Science & Engineering (Cyber Security)', 3, 'BSC', 'THEORY', 4, 3, 1, 0)
    ON CONFLICT (course_code) DO UPDATE SET
        course_name = EXCLUDED.course_name,
        program = EXCLUDED.program,
        department = EXCLUDED.department,
        semester = EXCLUDED.semester,
        category = EXCLUDED.category,
        course_type = EXCLUDED.course_type,
        credits = EXCLUDED.credits,
        periods_l = EXCLUDED.periods_l,
        periods_t = EXCLUDED.periods_t,
        periods_p = EXCLUDED.periods_p
    RETURNING id INTO v_course_id;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 1, 'Laplace Transform', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Laplace Transform Definition and Existence Theorems')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Transforms of Derivatives and Integrals')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Unit Step and Dirac-Delta Functions')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Inverse Laplace Transform and Convolution Theorem')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 5, 'Application to Linear Differential Equations')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 2, 'Fourier Transforms', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Fourier Integral and Complex Form')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Fourier Sine and Cosine Transforms')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Convolution Theorem for Fourier Transforms')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Applications to One-Dimensional Heat Equations')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 3, 'Algebraic & Transcendental Equations and Interpolation', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Roots of Equations: Bisection, Iteration, Newton-Raphson, Regula-Falsi')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Finite Differences and Operator Relations')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Newton Forward and Backward Interpolation')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Newton Divided Difference and Lagrange Interpolation')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 4, 'Numerical Differentiation, Integration and ODE', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Numerical Differentiation Techniques')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Numerical Integration: Trapezoidal, Simpson 1/3 and 3/8 Rules')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Runge-Kutta 4th Order Method for ODE')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Milne Predictor-Corrector Method')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 5, 'Statistical Techniques', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Measures of Central Tendency, Moments, Skewness, Kurtosis')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Curve Fitting and Method of Least Squares')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Correlation and Rank Correlation')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Linear and Non-Linear Regression Analysis')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    -- Course: ECT-033 - Digital Electronics
    INSERT INTO courses (id, course_code, course_name, program, department, semester, category, course_type, credits, periods_l, periods_t, periods_p)
    VALUES (gen_random_uuid(), 'ECT-033', 'Digital Electronics', 'B.Tech', 'Computer Science & Engineering (Cyber Security)', 3, 'ESC', 'THEORY', 4, 3, 1, 0)
    ON CONFLICT (course_code) DO UPDATE SET
        course_name = EXCLUDED.course_name,
        program = EXCLUDED.program,
        department = EXCLUDED.department,
        semester = EXCLUDED.semester,
        category = EXCLUDED.category,
        course_type = EXCLUDED.course_type,
        credits = EXCLUDED.credits,
        periods_l = EXCLUDED.periods_l,
        periods_t = EXCLUDED.periods_t,
        periods_p = EXCLUDED.periods_p
    RETURNING id INTO v_course_id;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 1, 'Minimization of Logic Functions', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Logic Gates and Boolean Algebra Review')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'SOP, POS, and Canonical Forms')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'K-Maps and Quine-McCluskey Minimization')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Binary Codes and Code Conversion')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 2, 'Combinational Circuits', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Adders, Subtractors, and ALU Design')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Code Converters and Comparators')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Decoders, Encoders, Multiplexers, and Demultiplexers')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Parity Checkers and Display Drivers')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 3, 'Sequential Circuits', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Latches and Flip-Flops (SR, JK, Master-Slave, Edge-Triggered)')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Counters (Ripple and Synchronous)')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Shift Registers and Universal Shift Registers')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Finite State Machines and Synchronous Sequential Design')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 4, 'Logic Families and Semiconductor Memories', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Logic Families (TTL, ECL, CMOS) and Interfacing')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Memory Elements and Semiconductor Memories')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Programmable Logic Devices (PLDs and FPGAs)')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 5, 'VLSI Design Flow', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'VLSI Design Entry: Schematic, FSM, and HDL')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'VHDL Modeling Styles (Dataflow, Behavioral, Structural)')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'VHDL Synthesis and Simulation for Digital Circuits')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    -- Course: AHT-007 - Technical Communication
    INSERT INTO courses (id, course_code, course_name, program, department, semester, category, course_type, credits, periods_l, periods_t, periods_p)
    VALUES (gen_random_uuid(), 'AHT-007', 'Technical Communication', 'B.Tech', 'Computer Science & Engineering (Cyber Security)', 3, 'HSC', 'THEORY', 3, 2, 1, 0)
    ON CONFLICT (course_code) DO UPDATE SET
        course_name = EXCLUDED.course_name,
        program = EXCLUDED.program,
        department = EXCLUDED.department,
        semester = EXCLUDED.semester,
        category = EXCLUDED.category,
        course_type = EXCLUDED.course_type,
        credits = EXCLUDED.credits,
        periods_l = EXCLUDED.periods_l,
        periods_t = EXCLUDED.periods_t,
        periods_p = EXCLUDED.periods_p
    RETURNING id INTO v_course_id;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 1, 'Fundamentals of Technical Communication', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Introduction and Features of Technical Communication')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Distinction Between General and Technical Communication')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Flow of Communication and Communication Barriers')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 2, 'Forms of Technical Communication', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Technical Reports, Theses, and Synopsis Writing')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Technical Research Papers, Seminars, and Conferences')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, '7 Cs of Effective Business Writing')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 3, 'Technical Presentation Strategies and Techniques', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Presentation Forms and Delivery Modes')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Public Speaking and Overcoming Stage Fear')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Audience Analysis and Interactive Quizzing')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 4, 'Technical Communication Skills', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Interview Skills and Group Discussions')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Argumentation, Analysis, and Critical Thinking')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Exposition, Narration, and Description Nuances')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 5, 'Kinesics and Voice Dynamics', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Kinesics and Body Language Principles')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Voice Modulation: Quality, Pitch, and Rhythm')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Phonetics, Intonation, and Articulation')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    -- Course: AHT-008 - Universal Human Values
    INSERT INTO courses (id, course_code, course_name, program, department, semester, category, course_type, credits, periods_l, periods_t, periods_p)
    VALUES (gen_random_uuid(), 'AHT-008', 'Universal Human Values', 'B.Tech', 'Computer Science & Engineering (Cyber Security)', 3, 'HSC', 'THEORY', 3, 2, 1, 0)
    ON CONFLICT (course_code) DO UPDATE SET
        course_name = EXCLUDED.course_name,
        program = EXCLUDED.program,
        department = EXCLUDED.department,
        semester = EXCLUDED.semester,
        category = EXCLUDED.category,
        course_type = EXCLUDED.course_type,
        credits = EXCLUDED.credits,
        periods_l = EXCLUDED.periods_l,
        periods_t = EXCLUDED.periods_t,
        periods_p = EXCLUDED.periods_p
    RETURNING id INTO v_course_id;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 1, 'Introduction to Value Education', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Universal Human Values and Self-Exploration')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Continuous Happiness and Prosperity as Human Aspirations')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Right Understanding, Relationships, and Physical Facilities')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 2, 'Harmony in the Human Being', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Understanding Self (I) and Body Distinction')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Characteristics, Activities, and Harmony within I')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Harmony with the Body: Sanyam and Health')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 3, 'Harmony in the Family and Society', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Values in Relationships: Trust and Respect')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Intention vs. Competence in Human Interactions')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Universal Harmonious Order and Society Coexistence')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 4, 'Harmony in Nature and Existence', 5)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Four Orders in Nature and Interconnectedness')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Existence as Coexistence at All Levels')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 5, 'Harmony in Professional Ethics', 5)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Natural Acceptance of Human Values and Ethical Conduct')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Competence in Professional Ethics and Case Studies')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Transition to Universal Human Order')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    -- Course: CST-002 - Discrete Structure
    INSERT INTO courses (id, course_code, course_name, program, department, semester, category, course_type, credits, periods_l, periods_t, periods_p)
    VALUES (gen_random_uuid(), 'CST-002', 'Discrete Structure', 'B.Tech', 'Computer Science & Engineering (Cyber Security)', 3, 'DC', 'THEORY', 4, 3, 1, 0)
    ON CONFLICT (course_code) DO UPDATE SET
        course_name = EXCLUDED.course_name,
        program = EXCLUDED.program,
        department = EXCLUDED.department,
        semester = EXCLUDED.semester,
        category = EXCLUDED.category,
        course_type = EXCLUDED.course_type,
        credits = EXCLUDED.credits,
        periods_l = EXCLUDED.periods_l,
        periods_t = EXCLUDED.periods_t,
        periods_p = EXCLUDED.periods_p
    RETURNING id INTO v_course_id;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 1, 'Set Theory and Functions', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Sets, Set Operations, Power Sets, and Cartesian Products')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Relations, Equivalence Relations, and Partitions')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Mathematical Induction')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Functions: Injective, Surjective, Bijective, and Inverses')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 2, 'Propositional Calculus and Lattices', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Propositions, Truth Tables, Tautologies, and Contradictions')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Formal Logic and Inference Rules')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Partial Orderings and Posets')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Lattice Theory and Special Lattices')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 3, 'Combinatorics and Recurrence Relations', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Counting Principles, Permutations, and Combinations')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Pigeonhole Principle and Inclusion-Exclusion')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Linear Recurrence Relations with Constant Coefficients')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Generating Functions for Recurrence Solutions')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 4, 'Algebraic Structures', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Semigroups, Monoids, and Groups')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Subgroups, Normal Subgroups, and Cyclic Groups')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Homomorphism, Isomorphism, and Automorphism')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Rings, Integral Domains, and Fields')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 5, 'Graphs and Trees', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Graph Terminology, Directed and Undirected Graphs')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Paths, Circuits, Eulerian and Hamiltonian Graphs')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Planar Graphs and Euler Formula')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Trees, Spanning Trees, and Binary Tree Traversals')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    -- Course: CST-003 - Data Structures and Algorithms
    INSERT INTO courses (id, course_code, course_name, program, department, semester, category, course_type, credits, periods_l, periods_t, periods_p)
    VALUES (gen_random_uuid(), 'CST-003', 'Data Structures and Algorithms', 'B.Tech', 'Computer Science & Engineering (Cyber Security)', 3, 'DC', 'THEORY', 4, 3, 1, 0)
    ON CONFLICT (course_code) DO UPDATE SET
        course_name = EXCLUDED.course_name,
        program = EXCLUDED.program,
        department = EXCLUDED.department,
        semester = EXCLUDED.semester,
        category = EXCLUDED.category,
        course_type = EXCLUDED.course_type,
        credits = EXCLUDED.credits,
        periods_l = EXCLUDED.periods_l,
        periods_t = EXCLUDED.periods_t,
        periods_p = EXCLUDED.periods_p
    RETURNING id INTO v_course_id;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 1, 'Introduction & Algorithm Analysis', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Data Structure Operations and Asymptotic Notations')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Time-Space Trade-off Analysis')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Linear Search and Binary Search Analysis')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 2, 'Stacks and Queues', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Stack ADT and Stack Operations')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Expression Conversion (Infix, Prefix, Postfix) and Evaluation')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Queue ADT: Simple, Circular, and Priority Queues')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 3, 'Linked Lists', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Singly Linked List Operations and Complexity')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Doubly Linked List and Circular Linked List')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Linked Representation of Stacks and Queues')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 4, 'Trees and Graphs', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Binary Trees and Binary Search Trees (BST)')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Balanced Trees: AVL Trees, B-Trees, and B+ Trees')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Graph Representations and Traversals (BFS and DFS)')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 5, 'Sorting and Hashing', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Comparison Sorts: Selection, Bubble, and Insertion Sort')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Divide & Conquer Sorts: Quick Sort, Merge Sort, and Heap Sort')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Hashing, Hash Functions, and Collision Resolution Techniques')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    -- Course: CST-004 - Object Oriented Programming
    INSERT INTO courses (id, course_code, course_name, program, department, semester, category, course_type, credits, periods_l, periods_t, periods_p)
    VALUES (gen_random_uuid(), 'CST-004', 'Object Oriented Programming', 'B.Tech', 'Computer Science & Engineering (Cyber Security)', 3, 'DC', 'THEORY', 4, 3, 1, 0)
    ON CONFLICT (course_code) DO UPDATE SET
        course_name = EXCLUDED.course_name,
        program = EXCLUDED.program,
        department = EXCLUDED.department,
        semester = EXCLUDED.semester,
        category = EXCLUDED.category,
        course_type = EXCLUDED.course_type,
        credits = EXCLUDED.credits,
        periods_l = EXCLUDED.periods_l,
        periods_t = EXCLUDED.periods_t,
        periods_p = EXCLUDED.periods_p
    RETURNING id INTO v_course_id;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 1, 'OOP Concepts & C++ Basics', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Classes, Objects, Abstraction, and Encapsulation')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Constructors, Destructors, and Static Class Members')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Friend Functions, Friend Classes, and Inline Functions')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Pointers, References, and Dynamic Memory Allocation')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 2, 'Function Overloading and Operator Overloading', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Function Overloading and Default Arguments')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Copy Constructors and Overloading Constructors')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Operator Overloading Using Member and Friend Functions')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Overloading new and delete Operators')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 3, 'Inheritance and Polymorphism', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Types of Inheritance and Base-Class Access Control')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Virtual Base Classes and Multiple Inheritance')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Virtual Functions and Runtime Polymorphism')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'RTTI and Type Casting Operators')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 4, 'Templates and Exception Handling', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Generic Functions and Class Templates')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Exception Handling Fundamentals: try, catch, and throw')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Standard Exception Classes and Custom Handlers')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 5, 'I/O Streams & Standard Template Library (STL)', 8)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'C++ Stream Classes and Formatted I/O')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'File Streams and File I/O Operations')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Standard Template Library: Containers, Iterators, and Algorithms')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    -- Course: CSP-003 - Data Structures and Algorithms Lab
    INSERT INTO courses (id, course_code, course_name, program, department, semester, category, course_type, credits, periods_l, periods_t, periods_p)
    VALUES (gen_random_uuid(), 'CSP-003', 'Data Structures and Algorithms Lab', 'B.Tech', 'Computer Science & Engineering (Cyber Security)', 3, 'DLC', 'LAB', 1, 0, 0, 2)
    ON CONFLICT (course_code) DO UPDATE SET
        course_name = EXCLUDED.course_name,
        program = EXCLUDED.program,
        department = EXCLUDED.department,
        semester = EXCLUDED.semester,
        category = EXCLUDED.category,
        course_type = EXCLUDED.course_type,
        credits = EXCLUDED.credits,
        periods_l = EXCLUDED.periods_l,
        periods_t = EXCLUDED.periods_t,
        periods_p = EXCLUDED.periods_p
    RETURNING id INTO v_course_id;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 1, 'List of Experiments', 28)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Stack and Queue ADT Implementation using Array')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Stack and Queue Implementation using Singly Linked List')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Double-Ended Queue (Deque) using Doubly Linked List')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Binary Search Tree Operations (Insert, Delete, Search)')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 5, 'Circular Queue ADT Implementation')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 6, 'Dictionary ADT Implementation using Hashing')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 7, 'AVL Tree and B-Tree Operations')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 8, 'Graph Traversals: Breadth First Search (BFS) and Depth First Search (DFS)')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 9, 'Minimum Spanning Tree (Prims and Kruskals Algorithms)')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 10, 'Single Source Shortest Path (Dijkstras Algorithm)')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 11, 'Binary Tree Non-Recursive Traversals (Pre-order, In-order, Post-order)')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 12, 'Sorting Algorithms Implementation (Quick Sort and Merge Sort)')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    -- Course: CSP-004 - Object Oriented Programming Lab
    INSERT INTO courses (id, course_code, course_name, program, department, semester, category, course_type, credits, periods_l, periods_t, periods_p)
    VALUES (gen_random_uuid(), 'CSP-004', 'Object Oriented Programming Lab', 'B.Tech', 'Computer Science & Engineering (Cyber Security)', 3, 'DLC', 'LAB', 1, 0, 0, 2)
    ON CONFLICT (course_code) DO UPDATE SET
        course_name = EXCLUDED.course_name,
        program = EXCLUDED.program,
        department = EXCLUDED.department,
        semester = EXCLUDED.semester,
        category = EXCLUDED.category,
        course_type = EXCLUDED.course_type,
        credits = EXCLUDED.credits,
        periods_l = EXCLUDED.periods_l,
        periods_t = EXCLUDED.periods_t,
        periods_p = EXCLUDED.periods_p
    RETURNING id INTO v_course_id;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 1, 'List of Experiments', 28)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Classes with Static Members and Friend Functions')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Matrix Class with Dynamic Memory Allocation and Assignment Overloading')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Complex Number Class with Operator Overloading and Type Conversions')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Custom Dynamic Memory Allocation Overloading new and delete')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 5, 'Class Hierarchy for Multiple and Multilevel Inheritances')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 6, 'Dynamic Polymorphism and RTTI Demonstration')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 7, 'Linked List Class Template and Generic Algorithms')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 8, 'Sorting Algorithm Templates (Bubble, Insertion, Quick Sort)')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 9, 'Stack and Queue Classes with Exception Handling')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 10, 'File I/O for Persistent Object Data Processing')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    -- Course: CSP-005 - Python Programming Lab
    INSERT INTO courses (id, course_code, course_name, program, department, semester, category, course_type, credits, periods_l, periods_t, periods_p)
    VALUES (gen_random_uuid(), 'CSP-005', 'Python Programming Lab', 'B.Tech', 'Computer Science & Engineering (Cyber Security)', 3, 'DLC', 'LAB', 1, 0, 0, 2)
    ON CONFLICT (course_code) DO UPDATE SET
        course_name = EXCLUDED.course_name,
        program = EXCLUDED.program,
        department = EXCLUDED.department,
        semester = EXCLUDED.semester,
        category = EXCLUDED.category,
        course_type = EXCLUDED.course_type,
        credits = EXCLUDED.credits,
        periods_l = EXCLUDED.periods_l,
        periods_t = EXCLUDED.periods_t,
        periods_p = EXCLUDED.periods_p
    RETURNING id INTO v_course_id;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 1, 'List of Experiments', 28)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Interactive Interpreter and Python Script Execution')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Operators, Distance Calculation, and Command-Line Arguments')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Conditional Statements and Loop Constructs')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Prime Numbers, Fibonacci Series, and Basic Searching/Sorting')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 5, 'String Manipulation and Dictionary Operations')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 6, 'List and Dictionary Aggregation')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 7, 'File Reading, Writing, and Word Counting')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 8, 'Functions, Geometric Collisions, and Statistical Measures')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 9, 'String Distance, List Deduplication, and Uniqueness')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 10, 'Recursive Functions, Inversions, and GCD/LCM Computations')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 11, 'Scientific Packages: NumPy, Pandas, Matplotlib, and Requests')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    -- Course: CSP-006 - Internship-I / Mini Project-I
    INSERT INTO courses (id, course_code, course_name, program, department, semester, category, course_type, credits, periods_l, periods_t, periods_p)
    VALUES (gen_random_uuid(), 'CSP-006', 'Internship-I / Mini Project-I', 'B.Tech', 'Computer Science & Engineering (Cyber Security)', 3, 'DLC', 'LAB', 1, 0, 0, 2)
    ON CONFLICT (course_code) DO UPDATE SET
        course_name = EXCLUDED.course_name,
        program = EXCLUDED.program,
        department = EXCLUDED.department,
        semester = EXCLUDED.semester,
        category = EXCLUDED.category,
        course_type = EXCLUDED.course_type,
        credits = EXCLUDED.credits,
        periods_l = EXCLUDED.periods_l,
        periods_t = EXCLUDED.periods_t,
        periods_p = EXCLUDED.periods_p
    RETURNING id INTO v_course_id;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 1, 'Project Work & Assessment', 28)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Problem Identification and Industrial Requirement Gathering')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'System Architecture, Design, and Technology Stack Selection')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Module Implementation, Integration, and Verification')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Technical Documentation, Presentation, and Viva Voce')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    -- Course: CST-005 - Python Programming
    INSERT INTO courses (id, course_code, course_name, program, department, semester, category, course_type, credits, periods_l, periods_t, periods_p)
    VALUES (gen_random_uuid(), 'CST-005', 'Python Programming', 'B.Tech', 'Computer Science & Engineering (Cyber Security)', 3, 'MC', 'THEORY', 0, 2, 0, 0)
    ON CONFLICT (course_code) DO UPDATE SET
        course_name = EXCLUDED.course_name,
        program = EXCLUDED.program,
        department = EXCLUDED.department,
        semester = EXCLUDED.semester,
        category = EXCLUDED.category,
        course_type = EXCLUDED.course_type,
        credits = EXCLUDED.credits,
        periods_l = EXCLUDED.periods_l,
        periods_t = EXCLUDED.periods_t,
        periods_p = EXCLUDED.periods_p
    RETURNING id INTO v_course_id;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 1, 'Introduction and Syntax of Python', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Features of Python, Building Blocks, and Environment Setup')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Identifiers, Keywords, Indentation, and Variables')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Python Data Types: Numbers, Strings, Tuples, Lists, Dictionaries')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 2, 'Operators and Control Flow Statements', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Arithmetic, Relational, Logical, and Bitwise Operators')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Conditional Statements: if, if-else, nested if')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Looping in Python: while loop, for loop, nested loops')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Loop Manipulation: break, continue, pass')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 3, 'Data Structures in Python', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'String Operations, Escape Characters, and Formatting')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'List Operations, Slicing, and Built-in List Methods')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Tuple Operations and Set Operations')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Dictionary Operations and Built-in Dictionary Methods')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 4, 'Functions, Modules, and Packages', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Built-in Functions and Type Conversion')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'User-Defined Functions and Parameter Passing')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Scope of Variables: Global and Local Variables')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Creating and Importing Python Modules and Packages')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 5, 'File Handling', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Opening Files in Different Modes (Read, Write, Append)')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Reading and Writing Files using Standard Library')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Closing, Renaming, and Deleting Files')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    -- Course: CST-006 - Cyber Security
    INSERT INTO courses (id, course_code, course_name, program, department, semester, category, course_type, credits, periods_l, periods_t, periods_p)
    VALUES (gen_random_uuid(), 'CST-006', 'Cyber Security', 'B.Tech', 'Computer Science & Engineering (Cyber Security)', 3, 'MC', 'THEORY', 0, 2, 0, 0)
    ON CONFLICT (course_code) DO UPDATE SET
        course_name = EXCLUDED.course_name,
        program = EXCLUDED.program,
        department = EXCLUDED.department,
        semester = EXCLUDED.semester,
        category = EXCLUDED.category,
        course_type = EXCLUDED.course_type,
        credits = EXCLUDED.credits,
        periods_l = EXCLUDED.periods_l,
        periods_t = EXCLUDED.periods_t,
        periods_p = EXCLUDED.periods_p
    RETURNING id INTO v_course_id;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 1, 'Introduction to Cyber Security', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Cyber Security Concepts, CIA Triad, and Layers of Security')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Vulnerabilities, Threats, and Attack Taxonomy')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Active Attacks, Passive Attacks, and IP Spoofing')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Cyber Warfare, Cyber Espionage, and Risk Management')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 2, 'Cyber Forensics', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Digital Forensics Science and Evidence Collection')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'The Need for Computer Forensics and Legal Evidence')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Digital Forensics Lifecycle and Email Investigation')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Forensic Auditing Challenges and Special Techniques')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 3, 'Cybercrime in Mobile and Wireless Devices', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Proliferation of Mobile Devices and Security Challenges')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Mobile Authentication Services and Credit Card Frauds')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Attacks on Mobile Phones and Registry Settings')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Organizational Security Policies for Mobile and Laptops')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 4, 'Organizational Implications and Cybercrime', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Cost of Cybercrimes and Intellectual Property Issues in Cyberspace')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'Web Threats and Social Media Marketing Risks for Organizations')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Psychology, Mindset, and Skills of Hackers and Cybercriminals')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

    INSERT INTO course_units (id, course_id, unit_number, unit_title, teaching_hours)
    VALUES (gen_random_uuid(), v_course_id, 5, 'Cyberspace Law and IT Act Provisions', 6)
    ON CONFLICT (course_id, unit_number) DO UPDATE SET
        unit_title = EXCLUDED.unit_title,
        teaching_hours = EXCLUDED.teaching_hours
    RETURNING id INTO v_unit_id;

    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 1, 'Cybersecurity Regulations and International Laws')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 2, 'National Cyber Security Policy and Internet Governance')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 3, 'Offences under the Indian Information Technology Act 2008')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;
    INSERT INTO unit_topics (id, unit_id, topic_order, topic_name)
    VALUES (gen_random_uuid(), v_unit_id, 4, 'Admissibility and Role of Electronic Evidence')
    ON CONFLICT (unit_id, topic_order) DO UPDATE SET topic_name = EXCLUDED.topic_name;

END $$;