-- Fix wrong logo URL for SickKids_TheHospital (was resolving sickkidsthehospital.com, correct is sickkids.ca)
UPDATE managers
SET company_logo_url = 'https://img.logo.dev/sickkids.ca?token=pk_MXSjJV-uTC6-L5D_FbXZUA'
WHERE LOWER(TRIM(company)) = 'sickkids_thehospital'
  AND company_logo_url LIKE '%sickkidsthehospital.com%';

UPDATE companies
SET logo_url = 'https://img.logo.dev/sickkids.ca?token=pk_MXSjJV-uTC6-L5D_FbXZUA'
WHERE LOWER(TRIM(name)) = 'sickkids_thehospital'
  AND logo_url LIKE '%sickkidsthehospital.com%';
