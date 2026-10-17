
export async function GET() {
    try {
        const baseUrl = process.env.PMS_API_BASE_URL;

        if (!baseUrl) {
            return Response.json(
                { message: "PMS API URL is not configured." },
                { status: 500 },
            );
        }

        const response = await fetch(
            `${baseUrl}/api/v1/reservations`,
            { method: "GET", cache: "no-store" },
        );

        const text = await response.text();

        let data: unknown = null;
        if (text) {
            try {
                data = JSON.parse(text);
            } catch {
                data = { message: text };
            }
        }

        return Response.json(data, { status: response.status });
    } catch {
        return Response.json(
            { message: "Could not connect to the PMS reservation service." },
            { status: 502 },
        );
    }
}

export async function POST(request: Request) {

    try {
        const baseUrl = process.env.PMS_API_BASE_URL;
        const propertyId = process.env.PMS_PROPERTY_ID;

        if (!baseUrl || !propertyId) {
            return Response.json(
                { message: "PMS API URL or property ID is not configured." },
                { status: 500 },
            );
        }

        const payload = await request.json();

        const response = await fetch(`${baseUrl}/api/v1/reservations`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                ...payload,
                propertyId,
            }),
            cache: "no-store",
        });

        const text = await response.text();

        let data: unknown = null;
        if (text) {
            try {
                data = JSON.parse(text);
            } catch {
                data = { message: text };
            }
        }

        return Response.json(data, { status: response.status });
    } catch {
        return Response.json(
            { message: "Could not connect to the PMS reservation service." },
            { status: 502 },
        );
    }
}
