-- NULL out company logo URLs that were stored with a doubled TLD (e.g. pricelinecom.com,
-- bookingcom.com) caused by the companyDomain bug that stripped dots before appending .com.
-- These broken URLs will be recomputed correctly on the next logo resolution.
UPDATE managers
SET company_logo_url = NULL
WHERE company_logo_url ~ 'img\.logo\.dev/[^?]*(com\.com|net\.com|io\.com|org\.com|co\.com)\?';

UPDATE companies
SET logo_url = NULL
WHERE logo_url ~ 'img\.logo\.dev/[^?]*(com\.com|net\.com|io\.com|org\.com|co\.com)\?';
