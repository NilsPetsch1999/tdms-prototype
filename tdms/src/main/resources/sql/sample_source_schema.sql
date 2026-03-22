-- Sample source schema that can be used for synthetic generation and masking demos.
-- Load it into the same MySQL instance:
--   mysql -u tdms_user -p tdms < src/main/resources/sql/sample_source_schema.sql

CREATE TABLE IF NOT EXISTS customers (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_number VARCHAR(30) NOT NULL UNIQUE,
    first_name VARCHAR(80) NOT NULL,
    last_name VARCHAR(80) NOT NULL,
    email VARCHAR(150) NOT NULL,
    phone VARCHAR(40) NULL,
    date_of_birth DATE NULL,
    street_address VARCHAR(150) NULL,
    city VARCHAR(120) NULL,
    postal_code VARCHAR(20) NULL,
    country_code CHAR(2) NOT NULL DEFAULT 'AT',
    segment VARCHAR(40) NOT NULL DEFAULT 'RETAIL',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS products (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    sku VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL,
    category VARCHAR(80) NOT NULL,
    brand VARCHAR(80) NULL,
    description VARCHAR(255) NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    currency_code CHAR(3) NOT NULL DEFAULT 'EUR',
    stock_quantity INT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS orders (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    order_number VARCHAR(40) NOT NULL,
    currency_code CHAR(3) NOT NULL DEFAULT 'EUR',
    subtotal_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    shipping_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    total_amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    payment_method VARCHAR(30) NULL,
    order_date DATE NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
);

CREATE TABLE IF NOT EXISTS order_items (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    line_total DECIMAL(12,2) NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products (id)
);

INSERT INTO customers (
    customer_number,
    first_name,
    last_name,
    email,
    phone,
    date_of_birth,
    street_address,
    city,
    postal_code,
    country_code,
    segment
)
VALUES
    ('CUST-1001', 'Nina', 'Huber', 'nina.huber@example.org', '+43 660 1234567', '1995-04-11', 'Mariahilfer Strasse 12', 'Vienna', '1060', 'AT', 'RETAIL'),
    ('CUST-1002', 'Lukas', 'Mayer', 'lukas.mayer@example.org', '+43 699 2345678', '1982-09-23', 'Landstrasse 44', 'Linz', '4020', 'AT', 'BUSINESS'),
    ('CUST-1003', 'Elena', 'Wagner', 'elena.wagner@example.org', '+43 676 3456789', '1989-01-15', 'Annenstrasse 8', 'Graz', '8020', 'AT', 'RETAIL');

INSERT INTO products (
    sku,
    name,
    category,
    brand,
    description,
    unit_price,
    currency_code,
    stock_quantity,
    active
)
VALUES
    ('LAP-14-PRO', 'Nimbus Pro 14 Laptop', 'Electronics', 'Nimbus', '14-inch business laptop with 16GB RAM and 512GB SSD', 1299.00, 'EUR', 25, TRUE),
    ('MON-27-4K', 'Vista 27 4K Monitor', 'Electronics', 'Vista', '27-inch UHD monitor with USB-C docking', 349.90, 'EUR', 40, TRUE),
    ('DOC-USB-C', 'Docking Station USB-C', 'Accessories', 'Orbit', 'USB-C docking station with HDMI and Ethernet', 149.50, 'EUR', 60, TRUE),
    ('CHR-ERG-01', 'Ergo Comfort Chair', 'Office', 'NorthSeat', 'Ergonomic office chair with lumbar support', 289.00, 'EUR', 18, TRUE);

INSERT INTO orders (
    customer_id,
    order_number,
    currency_code,
    subtotal_amount,
    shipping_amount,
    total_amount,
    status,
    payment_method,
    order_date
)
VALUES
    (1, 'PO-10001', 'EUR', 1648.90, 0.00, 1648.90, 'PAID', 'CREDIT_CARD', '2025-10-10'),
    (2, 'PO-10002', 'EUR', 289.00, 14.90, 303.90, 'PROCESSING', 'INVOICE', '2025-10-11'),
    (3, 'PO-10003', 'EUR', 149.50, 0.00, 149.50, 'SHIPPED', 'PAYPAL', '2025-10-12');

INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total)
VALUES
    (1, 1, 1, 1299.00, 1299.00),
    (1, 2, 1, 349.90, 349.90),
    (2, 4, 1, 289.00, 289.00),
    (3, 3, 1, 149.50, 149.50);
