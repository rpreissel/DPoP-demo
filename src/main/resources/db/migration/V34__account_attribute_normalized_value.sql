ALTER TABLE account_attribute ADD COLUMN normalized_value VARCHAR(255);

UPDATE account_attribute
SET normalized_value = lower(trim(attribute_value))
WHERE attribute_value IS NOT NULL;

-- Führendes attribute_type hält den Index für die typgebundenen Gleichheits-Lookups selektiv;
-- account_id ist mit aufgenommen, damit die Kandidatenabfrage allein aus dem Index beantwortet
-- werden kann.
CREATE INDEX idx_account_attribute_type_normalized
    ON account_attribute (attribute_type, normalized_value, account_id);
