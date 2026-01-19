# Test Data Management System (TDMS) – Prototype

## Overview
This repository contains a prototype implementation of a Test Data Management System (TDMS) developed in the context of a Master’s thesis.
 
The goal of the prototype is to demonstrate how synthetic and masked real test data can be generated, managed, versioned, and reused in a datenschutzkonformer (GDPR-aware) manner for software testing purposes.

The prototype focuses on conceptual clarity and architectural feasibility, not on a production-ready implementation.

## Objectives 
- Provide a centralized system for managing test data
- Support synthetic test data generation
- Support masking/anonymization of real datasets
- Enable versioning and reproducibility of test datasets
- Store and manage metadata describing test data
- Compare synthetic vs. masked real data in practice
- Demonstrate privacy-by-design principles

## High-Level Architecture

## Frontend
PWA Frontend (React)

#### Why PWA?

- Platform-independent (desktop & mobile)
- Lightweight
- Offline-capable (optional)
- Clean UI for academic demos

#### Responseablilities
- Create test data generation requests
- Configure masking rules
- Browse test data versions
- View metadata and lineage
- import real testdata
- connect to databases

## Backend
Java - Spring Boot 

#### Possible Backend Services: 
- Test Data Generator (using ai agents)
- Masking Engine
- Version Manager
- Metadata Service

#### Database + Object/File Storage
- MYSQL for Metadata
- Data could be stored as: SQL dumps, CSV, JSON, Object Storage 

## Backend Components

## Test Data Generator
Responsible for synthetic data creation.


### Approaches
- Schema-driven (tables, fields, constraints)
- seeding
- Deterministic & reproducible

Extension Optional:
- ai agent test data experimental

## Data Masking / Anonymization 
Responsible for transforming real data into privacy-preserving test data.

Supported Techniques

- Substitution (e.g., name → random name)
- Pseudonymization
- Tokenization
- Hashing
- Generalization (age → age group)


## Versioning System for Test Data

### Version Metadata
- Dataset ID
- Version number
- Creation timestamp
- Source (synthetic / masked real)
- Generation parameters
- Schema version
- Author / tool version
- etc...

### Storage Concept
Metadata in Relational DB
- Data stored as: SQL dumpx, CSV, JSON, Object Storage


## Privacy & GDPR Considerations
- No raw production data stored permanently
- Masking rules are explicit and documented
- Separation of metadata and data
- Privacy-by-design architecture
- Support for data minimization



## Evaluation Strategy

The prototype will be evaluated based on:

- Data realism
- Test suitability
- Privacy risk
- Generation effort
- Reproducibility

Synthetic and masked datasets are compared using identical test scenarios.


## Technology Stack Summary

- Frontend - PWA (React)
- Backend - Java / Spring Boot 
- API - REST
- Database - PostgreSQL
- Storage - File system

## Scope & Limitations

- Prototype, not production-ready
- Limited dataset sizes
- No full GDPR certification
- Focus on concept validation

## License

Academic prototype – non-production use.