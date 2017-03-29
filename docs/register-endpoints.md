Register ARN endpoints
----

* **URL**

  `/push/endpoint`

* **Method:**

  `POST`

  Add ARN endpoints to push registration records.
  
  Endpoints are associated with push registration records using a "token to endpoint" map. Push registration records which are no longer 'valid' can be removed by setting the endpoint value to `null`.
  
  Given the example JSON body below, the push registration records for `token_1` and `token_3` will be updated. The push registration record for `token_2` will be removed.

```json
{
  "token_1": "default-platform-arn:stubbed:default-platform-arn:token-1",
  "token_2": null,
  "token_3": "default-platform-arn:stubbed:default-platform-arn:token-3"
}
```

*  **URL Params**

   **None:**
 
* **Success Response:**
  * **Code:** 200 OK<br />
  
  * **Code:** 202 ACCEPTED<br />
  Returned when one or more of the push registration records cannot be updated, for example because the record was previously removed.

* **Error Response:**

  * **Code:** 400 BAD_REQUEST <br />
  Returned when data errors occur, e.g. when JSON body cannot be parsed.

  * **Code:** 500 INTERNAL_SERVER_ERROR <br />
  On failure of the service.

