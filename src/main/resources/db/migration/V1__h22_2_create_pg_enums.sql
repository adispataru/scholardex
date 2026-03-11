CREATE SCHEMA IF NOT EXISTS reporting_read;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'edition_normalized_enum') THEN
        CREATE TYPE reporting_read.edition_normalized_enum AS ENUM (
            'SCIE',
            'SSCI',
            'AHCI',
            'ESCI',
            'OTHER',
            'UNKNOWN'
        );
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'metric_type_enum') THEN
        CREATE TYPE reporting_read.metric_type_enum AS ENUM (
            'AIS',
            'RIS',
            'IF'
        );
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'wos_source_type_enum') THEN
        CREATE TYPE reporting_read.wos_source_type_enum AS ENUM (
            'GOV_AIS_RIS',
            'OFFICIAL_WOS_EXTRACT'
        );
    END IF;
END $$;
