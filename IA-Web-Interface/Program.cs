using IA_Web_Interface.Components;

var builder = WebApplication.CreateBuilder(args);

// Add services to the container.
builder.Services.AddRazorComponents()
    .AddInteractiveServerComponents();

// Increase HttpClient timeout to tolerate longer RPC roundtrips (RabbitMQ / model latency)
builder.Services.AddScoped(sp => {
    var client = new HttpClient { BaseAddress = new Uri("http://localhost:8080/") };
    // Default is 100s — increase to 3 minutes to match backend reply-timeout (120s) plus margin
    client.Timeout = TimeSpan.FromMinutes(3);
    return client;
});


var app = builder.Build();


// Configure the HTTP request pipeline.
if (!app.Environment.IsDevelopment())
{
    app.UseExceptionHandler("/Error", createScopeForErrors: true);
    // The default HSTS value is 30 days. You may want to change this for production scenarios, see https://aka.ms/aspnetcore-hsts.
    app.UseHsts();
}
app.UseStatusCodePagesWithReExecute("/not-found", createScopeForStatusCodePages: true);
app.UseHttpsRedirection();

app.UseAntiforgery();

app.MapStaticAssets();
app.MapRazorComponents<App>()
    .AddInteractiveServerRenderMode();

app.Run();
